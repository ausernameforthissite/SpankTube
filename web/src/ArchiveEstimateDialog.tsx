import { ArchiveFilterForm } from "./ArchiveFilterForm";
import type { ArchiveEstimate, ArchiveEstimateProgress, ArchiveFilter } from "./types";

function formatBytes(bytes: number): string {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(2)} ГиБ`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} МиБ`;
  return `${Math.ceil(bytes / 1024)} КиБ`;
}

function formatRate(bytesPerSecond: number): string {
  return `${(bytesPerSecond / 1024 ** 2).toFixed(2)} МиБ/с`;
}

function formatDuration(seconds: number): string {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.ceil((seconds % 3600) / 60);
  return hours > 0 ? `≈${hours} ч ${minutes} мин` : `≈${minutes} мин`;
}

function formatAnalysisRate(videosPerSecond: number): string {
  return videosPerSecond > 0 ? `${videosPerSecond.toFixed(2)} видео/с` : "—";
}

export function ArchiveEstimateDialog({ title, estimate, progress, loading, defaultTargetHeight, onCancel, onConfirm, onRetry, onStart }: {
  title: string;
  estimate: ArchiveEstimate | null;
  progress: ArchiveEstimateProgress | null;
  loading: boolean;
  defaultTargetHeight: number | null;
  onCancel: () => void;
  onConfirm: () => void;
  onRetry: () => void;
  onStart: (filter: ArchiveFilter, targetHeight: number | null) => void;
}) {
  const progressMessage = !progress ? "Создаю оценку…"
    : progress.status === "COLLECTING" ? progress.pageNumber > 0
      ? `Открыта страница ${progress.pageNumber} · найдено видео: ${progress.discoveredVideoCount}`
      : "Собираю список видео…"
    : progress.status === "ESTIMATING" ? `Проанализировано видео: ${progress.processedVideoCount} из ${progress.totalVideoCount}`
    : progress.status === "FAILED" ? "Не удалось оценить объём загрузки"
    : progress.status === "CANCELLED" ? "Оценка остановлена" : "Завершаю оценку…";
  const configuring = !loading && !estimate && !progress;
  return <section className="archive-estimate" aria-label="Оценка архивации">
    <div className="popup-header"><strong>{title}</strong>
      <button className="popup-close" aria-label="Отменить архивацию" onClick={onCancel}>×</button>
    </div>
    {configuring ? <ArchiveFilterForm initialTargetHeight={defaultTargetHeight} onStart={onStart} /> : loading || !estimate ? <div className="archive-estimate-progress" aria-live="polite">
      <p role="status">{progressMessage}</p>
      {progress && <>
        {progress.status === "ESTIMATING" && progress.totalVideoCount > 0 &&
          <progress max={progress.totalVideoCount} value={progress.processedVideoCount} aria-label="Прогресс оценки" />}
        <div className="archive-estimate-stats">
          <span>Проанализировано видео: <strong>{progress.processedVideoCount}{progress.totalVideoCount > 0 ? ` из ${progress.totalVideoCount}` : ""}</strong></span>
          {progress.pageNumber > 0 && <>
            <span>Текущая страница: <strong>{progress.pageNumber}</strong></span>
            <span>Осталось проанализировать на странице: <strong>{progress.remainingOnPage}</strong></span>
          </>}
          <span>Скорость анализа: <strong>{formatAnalysisRate(progress.videosPerSecond)}</strong></span>
          {progress.skippedVideoCount > 0 && <span>Пропущено недоступных: <strong>{progress.skippedVideoCount}</strong></span>}
          {progress.filteredVideoCount > 0 && <span>Отфильтровано: <strong>{progress.filteredVideoCount}</strong></span>}
          {progress.totalBytes > 0 && <>
            <span>Новых видео: <strong>{progress.newVideoCount}</strong>, уже в архиве: {progress.existingVideoCount}</span>
            <span>Объём на сейчас: <strong>{formatBytes(progress.totalBytes)}</strong></span>
            {progress.estimatedSeconds > 0 && <span>Время на сейчас: <strong>{formatDuration(progress.estimatedSeconds)}</strong></span>}
          </>}
        </div>
        {progress.error && <p role="alert">{progress.error}</p>}
        {progress.status === "FAILED" && <button className="secondary" onClick={onRetry}>Повторить оценку</button>}
      </>}
    </div> : estimate && <>
      <div className="archive-estimate-stats">
        <span>Всего видео: <strong>{estimate.totalVideoCount}</strong></span>
        <span>Новых: <strong>{estimate.newVideoCount}</strong>, уже в архиве: {estimate.existingVideoCount}</span>
        {estimate.filteredVideoCount > 0 && <span>Отфильтровано: <strong>{estimate.filteredVideoCount}</strong></span>}
        <span>Видеодорожки: {formatBytes(estimate.videoBytes)}</span>
        <span>Аудиодорожки: {formatBytes(estimate.audioBytes)}</span>
        <span>Средняя скорость: видео {formatRate(estimate.videoBps)}, аудио {formatRate(estimate.audioBps)}</span>
      </div>
      {estimate.skippedVideoCount > 0 && <details className="archive-estimate-skipped">
        <summary>Пропущено недоступных видео: <strong>{estimate.skippedVideoCount}</strong></summary>
        <p>Они не вошли в расчёт объёма и не будут добавлены в очередь. Причина и рекомендации upstream:</p>
        <ul>{estimate.skippedVideos.map(video => <li key={video.videoId}>
          <code>{video.videoId}</code>: {video.reason}
        </li>)}</ul>
      </details>}
      <p>Примерный объём: <strong>{formatBytes(estimate.totalBytes)}</strong><br />
        Примерное время: <strong>{formatDuration(estimate.estimatedSeconds)}</strong></p>
      {estimate.newVideoCount + estimate.existingVideoCount > 0
        ? <button className="secondary" onClick={onConfirm}>Продолжить архивацию</button>
        : <p role="status">Нет видео, соответствующих выбранным условиям.</p>}
    </>}
  </section>;
}
