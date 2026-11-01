import type { QueueJob } from "./types";

const stages: Record<string, string> = {
  QUEUED: "В очереди",
  WAITING: "Ожидание повтора",
  METADATA: "Получение метаданных",
  DOWNLOADING: "Скачивание",
  SUBTITLES: "Субтитры",
  MUXING: "Сборка контейнера",
  CHAPTERS: "Главы SponsorBlock",
  FINISHING: "Завершение",
  COMPLETED: "Готово",
  FAILED: "Ошибка",
  EXPIRED: "Срок хранения истёк",
};

function bytes(value: number): string {
  if (!value) return "0 Б";
  const units = ["Б", "КиБ", "МиБ", "ГиБ", "ТиБ"];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return `${(value / 1024 ** index).toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
}

function active(job: QueueJob): boolean {
  return job.status === "queued" || job.status === "running";
}

export function QueuePanel({ jobs, beforeYou, onDownload, onClose, compact = false }: {
  jobs: QueueJob[];
  beforeYou: number;
  onDownload: (job: QueueJob) => void;
  onClose?: () => void;
  compact?: boolean;
}) {
  const ordered = [...jobs].sort((a, b) => Number(active(b)) - Number(active(a))
    || (active(a) ? a.priority - b.priority || a.createdAt.localeCompare(b.createdAt) : b.createdAt.localeCompare(a.createdAt)));
  return (
    <section className={`queue-content ${compact ? "compact" : ""}`} aria-label="Очередь скачивания">
      {onClose && <button className="popup-close" aria-label="Закрыть очередь" onClick={onClose}>×</button>}
      {beforeYou > 0 && <p className="queue-ahead">Перед вами в очереди {beforeYou} видео</p>}
      {ordered.length === 0 && <p className="muted">Заданий пока нет</p>}
      {ordered.map(job => {
        const total = Math.max(0, job.totalBytes);
        const downloaded = Math.min(job.bytesDownloaded, total > 0 ? total : job.bytesDownloaded);
        const remaining = Math.max(0, total - downloaded);
        return (
          <article className="queue-job" key={job.id}>
            <div className="queue-title">
              <div>
                <strong>{job.title}</strong>
                <div className="muted">{job.kind === "DOWNLOAD" ? "Скачать на устройство" : "Архивирование"} · {stages[job.stage] || job.stage}{job.currentVideoId ? ` · ${job.currentVideoId}` : ""}</div>
              </div>
              {job.downloadUrl && <button className="secondary" onClick={() => onDownload(job)}>Скачать файл</button>}
            </div>
            <label className="progress-label">Элементы: {job.completedCount}/{job.totalCount}{job.failedCount > 0 ? ` · ошибок ${job.failedCount}` : ""}</label>
            <progress max={job.totalCount} value={Math.min(job.completedCount + job.failedCount, job.totalCount)} aria-label="Прогресс по числу элементов" />
            {active(job) && <>
              <label className="progress-label">
                {bytes(downloaded)} / {total > 0 ? bytes(total) : "неизвестно"} · {remaining > 0 ? `осталось ${bytes(remaining)}` : "завершено"}
                {job.speedBps > 0 ? ` · ${bytes(job.speedBps)}/с` : ""}
              </label>
              {total > 0
                ? <progress max={total} value={downloaded} aria-label="Прогресс скачивания элемента" />
                : <div className="indeterminate" aria-label="Объём уточняется" />}
            </>}
            {job.error && <div className="error-inline">{job.error}</div>}
          </article>
        );
      })}
    </section>
  );
}
