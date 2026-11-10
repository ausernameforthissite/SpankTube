import { useEffect, useRef, useState } from "react";
import type shaka from "shaka-player/dist/shaka-player.compiled.js";

type QualityTrack = ReturnType<shaka.Player["getVariantTracks"]>[number];
type SettingsView = "closed" | "main" | "quality" | "speed" | "audio" | "subtitles";

const PLAYBACK_RATES = [
  { value: 0.25, label: "0,25" },
  { value: 0.5, label: "0,5" },
  { value: 0.75, label: "0,75" },
  { value: 1, label: "Обычная" },
  { value: 1.25, label: "1,25" },
  { value: 1.5, label: "1,5" },
  { value: 1.75, label: "1,75" },
  { value: 2, label: "2" },
];

export function PlayerSettingsMenu({ video, qualityTracks, selectedQuality, playbackRate, audioTracks, selectedAudio,
  subtitleTracks, selectedSubtitle, onSelectQuality, onPlaybackRateChange, onSelectAudio, onSelectSubtitle }: {
  video: HTMLVideoElement;
  qualityTracks: QualityTrack[];
  selectedQuality: "best" | "auto" | number;
  playbackRate: number;
  audioTracks: QualityTrack[];
  selectedAudio: number | "";
  subtitleTracks: shaka.extern.TextTrack[];
  selectedSubtitle: string;
  onSelectQuality: (value: string) => void;
  onPlaybackRateChange: (value: number) => void;
  onSelectAudio: (audioId: number) => void;
  onSelectSubtitle: (value: string) => void;
}) {
  const [view, setView] = useState<SettingsView>("closed");
  const rootRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (view === "closed") return;
    const frame = requestAnimationFrame(() => {
      const selected = menuRef.current?.querySelector<HTMLButtonElement>("[aria-checked='true']:not(:disabled)");
      const first = menuRef.current?.querySelector<HTMLButtonElement>("button:not(:disabled)");
      (selected || first)?.focus();
    });
    const pointerDown = (event: PointerEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setView("closed");
    };
    const keyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setView("closed");
        buttonRef.current?.focus();
      }
    };
    document.addEventListener("pointerdown", pointerDown);
    document.addEventListener("keydown", keyDown);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener("pointerdown", pointerDown);
      document.removeEventListener("keydown", keyDown);
    };
  }, [view]);

  const close = () => {
    setView("closed");
    buttonRef.current?.focus();
  };
  const chooseQuality = (value: string) => {
    onSelectQuality(value);
    close();
  };
  const choosePlaybackRate = (value: number) => {
    onPlaybackRateChange(value);
    close();
  };
  const chooseAudio = (value: number) => {
    onSelectAudio(value);
    close();
  };
  const chooseSubtitles = (value: string) => {
    onSelectSubtitle(value);
    close();
  };

  const selectedVideo = qualityTracks.find(track => track.id === selectedQuality);
  const quality = selectedQuality === "best" ? "Лучшее" : selectedQuality === "auto" ? "Авто"
    : selectedVideo ? `${selectedVideo.height}p` : "По умолчанию";
  const playbackRateLabel = PLAYBACK_RATES.find(rate => rate.value === playbackRate)?.label || String(playbackRate);
  const audio = audioTracks.find(track => track.audioId === selectedAudio);
  const subtitles = subtitleTracks.find(track => String(track.id) === selectedSubtitle);
  const audioLabel = audioTracks.length === 0 ? "Недоступно" : audio?.label || audio?.language || "Аудио";
  const subtitleLabel = selectedSubtitle === "off" ? "Без субтитров" : subtitles?.label || subtitles?.language || "Включены";
  const title = view === "quality" ? "Качество" : view === "speed" ? "Скорость воспроизведения"
    : view === "audio" ? "Звуковая дорожка" : "Субтитры";
  const compactSpeed = view === "speed" && window.matchMedia("(max-width: 640px)").matches;

  return (
    <div className="player-settings" ref={rootRef}>
      {view !== "closed" && <div className="player-settings-menu" id="player-settings-menu" ref={menuRef}
        role="menu" aria-label={view === "main" ? "Настройки плеера" : title}
        style={{ maxHeight: Math.max(120, video.clientHeight - (compactSpeed ? 8 : 64)) }}>
        {view === "main" ? <>
          <button type="button" className="player-settings-row" role="menuitem" disabled={audioTracks.length === 0}
            onClick={() => setView("audio")}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 18v-6a8 8 0 0 1 16 0v6" /><path d="M4 13h3v6H4zm13 0h3v6h-3z" /></svg>
            <span>Звуковая дорожка</span><span className="player-settings-value">{audioLabel}</span><span aria-hidden="true">›</span>
          </button>
          <button type="button" className="player-settings-row" role="menuitem" onClick={() => setView("subtitles")}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2" /><path d="M6 12h5m2 0h5M6 16h3m2 0h7" /></svg>
            <span>Субтитры</span><span className="player-settings-value">{subtitleLabel}</span><span aria-hidden="true">›</span>
          </button>
          <button type="button" className="player-settings-row" role="menuitem" onClick={() => setView("speed")}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></svg>
            <span>Скорость воспроизведения</span><span className="player-settings-value">{playbackRateLabel}</span><span aria-hidden="true">›</span>
          </button>
          <button type="button" className="player-settings-row" role="menuitem" disabled={qualityTracks.length === 0}
            onClick={() => setView("quality")}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h5m4 0h7M4 17h8m4 0h4M9 4v6m7 4v6" /></svg>
            <span>Качество</span><span className="player-settings-value">{quality}</span><span aria-hidden="true">›</span>
          </button>
        </> : <>
          <button type="button" className="player-settings-row player-settings-back" role="menuitem"
            aria-label="Назад к настройкам" onClick={() => setView("main")}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg>
            <span>{title}</span>
          </button>
          {view === "quality" && <>
            <button type="button" className="player-settings-row" role="menuitemradio"
              aria-checked={selectedQuality === "best"} onClick={() => chooseQuality("best")}>
              <span className="player-settings-check" aria-hidden="true">{selectedQuality === "best" ? "✓" : ""}</span>
              <span>Лучшее</span>
            </button>
            <button type="button" className="player-settings-row" role="menuitemradio"
              aria-checked={selectedQuality === "auto"} onClick={() => chooseQuality("auto")}>
              <span className="player-settings-check" aria-hidden="true">{selectedQuality === "auto" ? "✓" : ""}</span>
              <span>Авто</span>
            </button>
            {qualityTracks.map(track => <button type="button" className="player-settings-row" role="menuitemradio"
              key={track.id} aria-checked={selectedQuality === track.id} onClick={() => chooseQuality(String(track.id))}>
              <span className="player-settings-check" aria-hidden="true">{selectedQuality === track.id ? "✓" : ""}</span>
              <span>{track.height}p · {Math.round(track.bandwidth / 1000)} kbps</span>
            </button>)}
          </>}
          {view === "speed" && <div className="player-settings-speed-list">
            {PLAYBACK_RATES.map(rate => <button type="button" className="player-settings-row"
              role="menuitemradio" key={rate.value} aria-checked={playbackRate === rate.value}
              onClick={() => choosePlaybackRate(rate.value)}>
              <span className="player-settings-check" aria-hidden="true">{playbackRate === rate.value ? "✓" : ""}</span>
              <span>{rate.label}</span>
            </button>)}
          </div>}
          {view === "audio" && audioTracks.map(track => <button type="button" className="player-settings-row"
            role="menuitemradio" key={track.audioId} aria-checked={selectedAudio === track.audioId}
            onClick={() => track.audioId != null && chooseAudio(track.audioId)}>
            <span className="player-settings-check" aria-hidden="true">{selectedAudio === track.audioId ? "✓" : ""}</span>
            <span>{track.label || track.language || "Аудио"}</span>
          </button>)}
          {view === "subtitles" && <>
            <button type="button" className="player-settings-row" role="menuitemradio"
              aria-checked={selectedSubtitle === "off"} onClick={() => chooseSubtitles("off")}>
              <span className="player-settings-check" aria-hidden="true">{selectedSubtitle === "off" ? "✓" : ""}</span>
              <span>Без субтитров</span>
            </button>
            {subtitleTracks.map(track => <button type="button" className="player-settings-row" role="menuitemradio"
              key={track.id} aria-checked={selectedSubtitle === String(track.id)}
              onClick={() => chooseSubtitles(String(track.id))}>
              <span className="player-settings-check" aria-hidden="true">{selectedSubtitle === String(track.id) ? "✓" : ""}</span>
              <span>{track.label || track.language}</span>
            </button>)}
          </>}
        </>}
      </div>}
      <button type="button" className={`player-icon-button${view !== "closed" ? " active" : ""}`}
        ref={buttonRef} aria-label="Настройки плеера" title="Настройки" aria-haspopup="menu"
        aria-expanded={view !== "closed"} aria-controls={view !== "closed" ? "player-settings-menu" : undefined}
        onClick={() => setView(current => current === "closed" ? "main" : "closed")}>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path className="filled" d="M19.43 12.98c.04-.32.07-.65.07-.98s-.03-.66-.08-.98l2.11-1.65c.19-.15.24-.42.12-.64l-2-3.46c-.12-.22-.39-.3-.61-.22l-2.49 1c-.52-.4-1.08-.73-1.69-.98L14.5 2.42C14.47 2.18 14.25 2 14 2h-4c-.25 0-.46.18-.5.42L9.12 5.07c-.61.25-1.17.59-1.69.98l-2.49-1c-.23-.08-.49 0-.61.22l-2 3.46c-.13.22-.07.49.12.64l2.11 1.65c-.04.32-.08.65-.08.98s.03.66.08.98l-2.11 1.65c-.19.15-.24.42-.12.64l2 3.46c.12.22.39.3.61.22l2.49-1c.52.4 1.08.73 1.69.98l.38 2.65c.04.24.25.42.5.42h4c.25 0 .46-.18.5-.42l.38-2.65c.61-.25 1.17-.59 1.69-.98l2.49 1c.23.08.49 0 .61-.22l2-3.46c.13-.22.07-.49-.12-.64l-2.11-1.65zM12 15.5A3.5 3.5 0 1 1 12 8a3.5 3.5 0 0 1 0 7.5z" /></svg>
      </button>
    </div>
  );
}
