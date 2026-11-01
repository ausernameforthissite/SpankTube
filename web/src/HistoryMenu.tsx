import type { ArchiveVersion } from "./types";

function duration(seconds: number | null): string {
  if (seconds === null) return "—";
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = seconds % 60;
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, "0")}:${String(rest).padStart(2, "0")}`
    : `${minutes}:${String(rest).padStart(2, "0")}`;
}

export function HistoryMenu({ versions, onSelect, onClose }: {
  versions: ArchiveVersion[];
  onSelect: (version: ArchiveVersion) => void;
  onClose: () => void;
}) {
  return (
    <section className="history-options" aria-label="История версий видео">
      <div className="popup-header"><button className="popup-close" aria-label="Закрыть историю" onClick={onClose}>×</button></div>
      <h3>История версий</h3>
      {versions.length === 0 && <p className="muted">Версий пока нет</p>}
      <div className="history-list">
        {versions.map(version => (
          <button className={`history-version ${version.current ? "current" : ""}`} key={version.versionId}
            onClick={() => onSelect(version)}>
            <strong>{version.title}</strong>
            <span>{duration(version.durationSeconds)} · {(version.sizeBytes / 1_000_000).toFixed(1)} МБ</span>
            <span>{version.height ? `${version.height}p` : "—"} · {version.videoCodec || "—"} · {version.audioCodec || "—"}{version.current ? " · Активная" : ""}</span>
          </button>
        ))}
      </div>
    </section>
  );
}
