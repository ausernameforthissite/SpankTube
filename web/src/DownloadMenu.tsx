import { useState } from "react";
import type { VideoFormat } from "./types";

export type DownloadOptions = {
  videoFormatId: string | null;
  audioFormatId: string | null;
  subtitles: boolean;
  sponsorBlock: boolean;
};

export function DownloadMenu({ formats, onDownload, onClose }: {
  formats: VideoFormat[];
  onDownload: (options: DownloadOptions) => void;
  onClose: () => void;
}) {
  const video = formats.filter(format => format.hasVideo).sort((a, b) =>
    (b.height || 0) - (a.height || 0) || (b.bitrate || 0) - (a.bitrate || 0) || (b.fps || 0) - (a.fps || 0));
  const audio = formats.filter(format => format.hasAudio && !format.hasVideo)
    .sort((a, b) => (b.bitrate || 0) - (a.bitrate || 0));
  const bestVideo = video[0];
  const bestAudio = audio[0];
  const [videoMode, setVideoMode] = useState("best");
  const [audioMode, setAudioMode] = useState("best");
  const [subtitles, setSubtitles] = useState(true);
  const [sponsorBlock, setSponsorBlock] = useState(true);

  const submit = () => {
    const videoFormatId = videoMode === "none" ? null
      : videoMode === "best" ? audioMode === "none" || audioMode !== "best" ? bestVideo?.formatId || null : null : videoMode;
    const audioFormatId = audioMode === "none" ? null
      : audioMode === "best" ? videoMode === "none" || videoMode !== "best" ? bestAudio?.formatId || null : null : audioMode;
    onDownload({ videoFormatId, audioFormatId, subtitles, sponsorBlock });
  };

  return (
    <section className="download-options" aria-label="Вариант скачивания">
      <button className="popup-close" aria-label="Закрыть выбор скачивания" onClick={onClose}>×</button>
      <div className="format-columns">
        <fieldset>
          <legend>Видео</legend>
          <div className="option-list">
            <label><input type="radio" name="video-quality" value="best" checked={videoMode === "best"} onChange={event => setVideoMode(event.target.value)} />Лучшее{formatVideo(bestVideo)}</label>
            {video.map(format => <label key={format.formatId}>
              <input type="radio" name="video-quality" value={format.formatId} checked={videoMode === format.formatId} onChange={event => setVideoMode(event.target.value)} />
              {formatVideo(format)}
            </label>)}
            <label><input type="radio" name="video-quality" value="none" checked={videoMode === "none"} onChange={event => { setVideoMode(event.target.value); if (audioMode === "none") setAudioMode("best"); }} />Без видео (MP3)</label>
          </div>
        </fieldset>
        <fieldset>
          <legend>Аудио</legend>
          <div className="option-list">
            <label><input type="radio" name="audio-quality" value="best" checked={audioMode === "best"} onChange={event => setAudioMode(event.target.value)} />Лучшее{formatAudio(bestAudio)}</label>
            {audio.map(format => <label key={format.formatId}>
              <input type="radio" name="audio-quality" value={format.formatId} checked={audioMode === format.formatId} onChange={event => setAudioMode(event.target.value)} />
              {formatAudio(format)}
            </label>)}
            <label><input type="radio" name="audio-quality" value="none" checked={audioMode === "none"} onChange={event => { setAudioMode(event.target.value); if (videoMode === "none") setVideoMode("best"); }} />Без звука (MKV)</label>
          </div>
        </fieldset>
      </div>
      <div className="download-extras">
        <label><input type="checkbox" checked={subtitles} onChange={event => setSubtitles(event.target.checked)} />Сохранять субтитры</label>
        <label><input type="checkbox" checked={sponsorBlock} onChange={event => setSponsorBlock(event.target.checked)} />Сохранять сегменты SponsorBlock</label>
      </div>
      <button className="primary" disabled={videoMode === "none" && (audioMode === "none" || !bestAudio)
        || audioMode === "none" && !bestVideo} onClick={submit}>Скачать</button>
    </section>
  );
}

function formatVideo(format?: VideoFormat): string {
  if (!format) return "";
  return ` · ${format.height ? `${format.height}p` : "видео"} · ${format.codecs || "codec?"}`;
}

function formatAudio(format?: VideoFormat): string {
  if (!format) return "";
  return ` · ${format.bitrate ? `${Math.round(format.bitrate / 1000)} kbps · ` : ""}${format.codecs || "codec?"}`;
}
