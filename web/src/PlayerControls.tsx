import { useEffect, useRef, useState } from "react";
import type shaka from "shaka-player/dist/shaka-player.compiled.js";
import type { SponsorSegment } from "./types";

type QualityTrack = ReturnType<shaka.Player["getVariantTracks"]>[number];

const CATEGORY_COLORS: Record<string, string> = {
  sponsor: "#00d400",
  selfpromo: "#ffff00",
  exclusive_access: "#008a5c",
  interaction: "#cc00ff",
  intro: "#00ffff",
  outro: "#0202ed",
  preview: "#008fd6",
  music_offtopic: "#ff9900",
  poi_highlight: "#ff1684",
  filler: "#7300ff",
  chapter: "#ffd983",
};

const CATEGORY_LABELS: Record<string, string> = {
  sponsor: "Реклама",
  selfpromo: "Самореклама",
  exclusive_access: "Эксклюзивный доступ",
  interaction: "Призыв к действию",
  intro: "Заставка",
  outro: "Концовка",
  preview: "Превью",
  music_offtopic: "Музыка вне темы",
  poi_highlight: "Интересный момент",
  filler: "Отступление",
  chapter: "Глава",
};

function formatDuration(seconds: number): string {
  const rounded = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(rounded / 3600);
  const minutes = Math.floor((rounded % 3600) / 60);
  const rest = rounded % 60;
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, "0")}:${String(rest).padStart(2, "0")}`
    : `${minutes}:${String(rest).padStart(2, "0")}`;
}

export function PlayerControls({ video, player, position, sponsorSegments, autoplay, qualityTracks,
  selectedQuality, theater, onTogglePlayback, onSeek, onSelectQuality, onToggleAutoplay,
  onToggleTheater, onError }: {
  video: HTMLVideoElement;
  player: shaka.Player | null;
  position: number;
  sponsorSegments: SponsorSegment[];
  autoplay: boolean;
  qualityTracks: QualityTrack[];
  selectedQuality: "best" | "auto" | number;
  theater: boolean;
  onTogglePlayback: () => void;
  onSeek: (position: number) => void;
  onSelectQuality: (value: string) => void;
  onToggleAutoplay: () => void;
  onToggleTheater: () => void;
  onError: (reason: unknown) => void;
}) {
  const [paused, setPaused] = useState(video.paused);
  const [duration, setDuration] = useState(Number.isFinite(video.duration) ? video.duration : 0);
  const [buffered, setBuffered] = useState(0);
  const [volume, setVolume] = useState(video.volume);
  const [muted, setMuted] = useState(video.muted);
  const [subtitleCount, setSubtitleCount] = useState(0);
  const [subtitlesVisible, setSubtitlesVisible] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  const audibleVolume = useRef(video.volume || 1);

  useEffect(() => {
    const sync = () => {
      setPaused(video.paused);
      setDuration(Number.isFinite(video.duration) ? video.duration : 0);
      setVolume(video.volume);
      setMuted(video.muted);
      if (video.volume > 0) audibleVolume.current = video.volume;
      let end = 0;
      for (let index = 0; index < video.buffered.length; index++) {
        if (video.buffered.start(index) <= video.currentTime + 0.5) {
          end = Math.max(end, video.buffered.end(index));
        }
      }
      setBuffered(end);
    };
    sync();
    for (const event of ["play", "pause", "playing", "volumechange", "durationchange", "progress", "timeupdate", "loadedmetadata"]) {
      video.addEventListener(event, sync);
    }
    return () => {
      for (const event of ["play", "pause", "playing", "volumechange", "durationchange", "progress", "timeupdate", "loadedmetadata"]) {
        video.removeEventListener(event, sync);
      }
    };
  }, [video]);

  useEffect(() => {
    if (!player) {
      setSubtitleCount(0);
      setSubtitlesVisible(false);
      return;
    }
    const sync = () => {
      setSubtitleCount(player.getTextTracks().length);
      setSubtitlesVisible(Array.from(video.textTracks).some(track => track.mode === "showing"));
    };
    sync();
    player.addEventListener("trackschanged", sync);
    player.addEventListener("textchanged", sync);
    player.addEventListener("texttrackvisibility", sync);
    return () => {
      player.removeEventListener("trackschanged", sync);
      player.removeEventListener("textchanged", sync);
      player.removeEventListener("texttrackvisibility", sync);
    };
  }, [player]);

  useEffect(() => {
    const sync = () => setFullscreen(document.fullscreenElement === video.closest(".video-frame"));
    document.addEventListener("fullscreenchange", sync);
    sync();
    return () => document.removeEventListener("fullscreenchange", sync);
  }, [video]);

  const changeVolume = (value: number) => {
    video.volume = value;
    video.muted = value === 0;
    if (value > 0) audibleVolume.current = value;
  };

  const toggleSubtitles = () => {
    if (!player || subtitleCount === 0) return;
    const next = !subtitlesVisible;
    const tracks = player.getTextTracks();
    if (next && !tracks.some(track => track.active)) {
      const language = navigator.language.toLowerCase();
      const preferred = tracks.find(track => track.language.toLowerCase().startsWith(language))
        || tracks.find(track => track.language.toLowerCase().startsWith(language.split("-")[0])) || tracks[0];
      player.selectTextTrack(preferred);
    }
    player.setTextTrackVisibility(next);
    setSubtitlesVisible(next);
  };

  const toggleFullscreen = async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else await video.closest<HTMLElement>(".video-frame")?.requestFullscreen();
    } catch (reason) {
      onError(reason);
    }
  };

  const current = Math.max(0, Math.min(position, duration));
  const percentage = duration > 0 ? current / duration * 100 : 0;
  const bufferedPercentage = duration > 0 ? Math.min(buffered, duration) / duration * 100 : 0;

  return (
    <div className="player-controls" onClick={event => event.stopPropagation()}
      onMouseDown={event => { if (event.detail > 1) event.preventDefault(); }}
      onDoubleClick={event => { event.preventDefault(); event.stopPropagation(); }}>
      <div className="player-timeline">
        <div className="player-timeline-visual" aria-hidden="true">
          <div className="player-timeline-remaining" />
          <div className="player-timeline-buffered" style={{ width: `${bufferedPercentage}%` }} />
          <div className="player-timeline-elapsed" style={{ width: `${percentage}%` }} />
          {duration > 0 && sponsorSegments.map((segment, index) => {
            const start = Math.max(0, Math.min(segment.startSeconds, duration));
            const end = Math.max(start, Math.min(segment.endSeconds, duration));
            return end > start && <div key={`${segment.category}-${segment.startSeconds}-${index}`}
              className="player-timeline-segment"
              title={`${CATEGORY_LABELS[segment.category] || segment.category}: ${formatDuration(start)}–${formatDuration(end)}`}
              style={{ left: `${start / duration * 100}%`, width: `${(end - start) / duration * 100}%`,
                backgroundColor: CATEGORY_COLORS[segment.category] || "#ffffff", opacity: 0.7 }} />;
          })}
          <div className="player-timeline-thumb" style={{ left: `${percentage}%` }} />
        </div>
        <input className="player-seek-input" type="range" min={0} max={duration} step={0.1} value={current}
          disabled={duration <= 0} aria-label="Позиция видео"
          aria-valuetext={`${formatDuration(current)} из ${formatDuration(duration)}`}
          onChange={event => onSeek(Number(event.target.value))} />
      </div>
      <div className="player-controls-row">
        <div className="player-control-group">
          <button className="player-icon-button" aria-label={paused ? "Играть" : "Пауза"}
            title={paused ? "Играть" : "Пауза"} onClick={onTogglePlayback}>
            <svg viewBox="0 0 24 24" aria-hidden="true">{paused
              ? <path className="filled" d="M8 5v14l11-7z" />
              : <><path className="filled" d="M6 5h4v14H6z" /><path className="filled" d="M14 5h4v14h-4z" /></>}</svg>
          </button>
          <div className="volume-control">
            <button className="player-icon-button" aria-label={muted || volume === 0 ? "Включить звук" : "Выключить звук"}
              title={muted || volume === 0 ? "Включить звук" : "Выключить звук"} onClick={() => {
                if (muted || volume === 0) {
                  if (volume === 0) video.volume = audibleVolume.current;
                  video.muted = false;
                } else {
                  video.muted = true;
                }
              }}>
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M11 5 6 9H3v6h3l5 4z" />
                {muted || volume === 0
                  ? <><path d="m16 9 5 6" /><path d="m21 9-5 6" /></>
                  : <><path d="M15 9a4 4 0 0 1 0 6" />{volume >= 0.5 && <path d="M18 6a8 8 0 0 1 0 12" />}</>}
              </svg>
            </button>
            <input className="volume-slider" type="range" min={0} max={1} step={0.01}
              value={muted ? 0 : volume} aria-label="Громкость"
              onChange={event => changeVolume(Number(event.target.value))} />
          </div>
          <span className="player-time">{formatDuration(current)} / {formatDuration(duration)}</span>
        </div>
        <div className="player-control-group">
          <button className={`player-icon-button player-autoplay${autoplay ? " active" : ""}`}
            aria-label={`Автовоспроизведение: ${autoplay ? "включено" : "выключено"}`}
            aria-pressed={autoplay} title="Автовоспроизведение" onClick={onToggleAutoplay}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path className="filled" d="M8 5v14l11-7z" /><path d="M4 4v16" /></svg>
            <span aria-hidden="true">{autoplay ? "ON" : "OFF"}</span>
          </button>
          <select className="player-quality" aria-label="Качество видео" value={selectedQuality}
            disabled={qualityTracks.length === 0} onChange={event => onSelectQuality(event.target.value)}>
            <option value="best">Лучшее</option><option value="auto">Авто</option>
            {qualityTracks.map(track => <option key={track.id} value={track.id}>
              {track.height}p · {Math.round(track.bandwidth / 1000)} kbps
            </option>)}
          </select>
          <button className={`player-icon-button${subtitlesVisible ? " active" : ""}`}
            aria-label={subtitlesVisible ? "Выключить субтитры" : "Включить субтитры"}
            aria-pressed={subtitlesVisible} disabled={subtitleCount === 0}
            title={subtitleCount === 0 ? "Субтитры недоступны" : subtitlesVisible ? "Выключить субтитры" : "Включить субтитры"}
            onClick={toggleSubtitles}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2" /><path d="M7 10h3m4 0h3M7 14h3m4 0h3" /></svg>
          </button>
          <button className={`player-icon-button${theater ? " active" : ""}`}
            aria-label={theater ? "Выйти из театрального режима" : "Театральный режим"}
            aria-pressed={theater} title={theater ? "Обычный размер" : "Театральный режим"} onClick={onToggleTheater}>
            <svg viewBox="0 0 24 24" aria-hidden="true">{theater
              ? <path d="M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5" />
              : <path d="M3 8h5V3m8 0v5h5M8 21v-5H3m13 5v-5h5" />}</svg>
          </button>
          <button className="player-icon-button" aria-label={fullscreen ? "Выйти из полноэкранного режима" : "Полный экран"}
            title={fullscreen ? "Выйти из полноэкранного режима" : "Полный экран"} onClick={() => void toggleFullscreen()}>
            <svg viewBox="0 0 24 24" aria-hidden="true">{fullscreen
              ? <path d="M8 3v5H3m13-5v5h5M8 21v-5H3m13 5v-5h5" />
              : <path d="M3 8V3h5m8 0h5v5M3 16v5h5m8 0h5v-5" />}</svg>
          </button>
        </div>
      </div>
    </div>
  );
}
