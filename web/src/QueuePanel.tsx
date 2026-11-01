import type { QueueJob } from "./types";

const stages: Record<string, string> = {
  QUEUED: "В очереди",
  WAITING: "Ожидание повтора",
  METADATA: "Получение метаданных",
  DOWNLOADING: "Скачивание",
  DOWNLOADING_VIDEO: "Загрузка видеодорожки",
  DOWNLOADING_AUDIO: "Загрузка аудиодорожки",
  SUBTITLES: "Субтитры",
  SPONSORBLOCK: "Загрузка SponsorBlock",
  MUXING: "Мультиплексирование",
  CHAPTERS: "Разбиение на главы",
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

function updatedAt(value: string): string {
  const normalized = value.replace(" ", "T").replace(/([+-]\d{2})$/, "$1:00");
  const date = new Date(normalized);
  if (Number.isNaN(date.getTime())) return value;
  const now = new Date();
  const today = date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate();
  return new Intl.DateTimeFormat(undefined, today
    ? { timeStyle: "short" }
    : { dateStyle: "short", timeStyle: "short" }).format(date);
}

function DownloadIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12m0 0 5-5m-5 5-5-5M4 15v5h16v-5" /></svg>;
}

function DismissIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M9 7V4h6v3m-9 0 1 13h10l1-13M10 11v6m4-6v6" /></svg>;
}

export function QueuePanel({ jobs, beforeYou, onDownload, onDismiss, onNavigate, onClose, compact = false }: {
  jobs: QueueJob[];
  beforeYou: number;
  onDownload: (job: QueueJob) => void;
  onDismiss: (job: QueueJob) => void;
  onNavigate: (url: string) => void;
  onClose?: () => void;
  compact?: boolean;
}) {
  const ordered = [...jobs].sort((a, b) => Number(active(b)) - Number(active(a))
    || (active(a) ? a.priority - b.priority || a.createdAt.localeCompare(b.createdAt) : b.createdAt.localeCompare(a.createdAt)));
  return (
    <section className={`queue-content ${compact ? "compact" : ""}`} aria-label="Очередь скачивания">
      {onClose && <div className="popup-header"><button className="popup-close" aria-label="Закрыть очередь" onClick={onClose}>×</button></div>}
      <div className={onClose ? "queue-list queue-list-with-close" : "queue-list"}>
        {beforeYou > 0 && <p className="queue-ahead">Перед вами в очереди {beforeYou} видео</p>}
        {ordered.length === 0 && <p className="muted">Заданий пока нет</p>}
        {ordered.map(job => {
          const total = Math.max(0, job.totalBytes);
          const downloaded = Math.min(job.bytesDownloaded, total > 0 ? total : job.bytesDownloaded);
          const remaining = Math.max(0, total - downloaded);
          const videoId = job.currentVideoId || job.targetId;
          const watchUrl = `/watch?v=${encodeURIComponent(videoId)}`;
          return (
            <article className="queue-job" key={job.id}>
              <div className="queue-title">
                <div>
                  <a className="queue-video-link" href={watchUrl} onClick={event => {
                    event.preventDefault();
                    onNavigate(watchUrl);
                  }}><strong>{job.title}</strong></a>
                  <div className="muted">{job.kind === "DOWNLOAD" ? "Скачать на устройство" : "Архивирование"} · {stages[job.stage] || job.stage}</div>
                  <div className="muted">Обновлено: {updatedAt(job.updatedAt)}</div>
                  <div className="muted">{job.height ? `${job.height}p` : "—"} · {job.videoCodec || "—"} · {job.audioCodec || "—"} · {bytes(job.totalBytes || job.bytesDownloaded)}</div>
                </div>
                <div className="queue-actions">
                  {job.downloadUrl && <button className="secondary icon-button" aria-label="Скачать файл" title="Скачать файл" onClick={() => onDownload(job)}><DownloadIcon /></button>}
                  {!active(job) && <button className="secondary icon-button" aria-label="Убрать" title="Убрать" onClick={() => onDismiss(job)}><DismissIcon /></button>}
                </div>
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
      </div>
    </section>
  );
}
