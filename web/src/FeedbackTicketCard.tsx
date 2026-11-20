import { useEffect, useRef, useState } from "react";
import { feedbackRequestId, useFeedbackDraft } from "./feedbackDrafts";
import { feedbackDate, feedbackPriority, feedbackResolution, feedbackStatus, feedbackType } from "./feedbackText";
import type { ApiFetch, FeedbackTicket } from "./types";

export function feedbackError(reason: unknown): string {
  return reason instanceof Error ? reason.message : String(reason);
}

type CommentDraft = { text: string; requestId: string };
const types: FeedbackTicket["type"][] = ["Question", "Bug", "Feature", "Improvement"];
const priorities: FeedbackTicket["priority"][] = ["Minor", "Major", "Critical", "Blocker"];

export function FeedbackTicketCard({ ticket, admin, username, apiFetch, onChanged, onNavigate }: {
  ticket: FeedbackTicket;
  admin: boolean;
  username: string;
  apiFetch: ApiFetch;
  onChanged: (ticket: FeedbackTicket) => void;
  onNavigate: (url: string) => void;
}) {
  const emptyComment = () => ({ text: "", requestId: feedbackRequestId() });
  const emptyReopen = () => ({ text: "", requestId: feedbackRequestId() });
  const [comment, setComment, clearComment] = useFeedbackDraft<CommentDraft>(
    `spanktube.feedback.${username}.${ticket.id}.comment`, emptyComment());
  const [reopen, setReopen, clearReopen] = useFeedbackDraft<CommentDraft>(
    `spanktube.feedback.${username}.${ticket.id}.reopen`, emptyReopen());
  const [reopenVisible, setReopenVisible] = useState(false);
  const [type, setType] = useState(ticket.type);
  const [priority, setPriority] = useState(ticket.priority);
  const [resolution, setResolution] = useState<"Answered" | "Rejected" | "Duplicate">("Answered");
  const [duplicateOf, setDuplicateOf] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [retryAction, setRetryAction] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const busyRef = useRef(false);
  const base = admin ? `/api/v1/admin/tickets/${ticket.id}` : `/api/v1/feedback/tickets/${ticket.id}`;

  useEffect(() => {
    setType(ticket.type);
    setPriority(ticket.priority);
  }, [ticket.type, ticket.priority]);

  const run = async (action: string, label: string, work: () => Promise<FeedbackTicket>, success?: () => void) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(action);
    setActionError(null);
    setRetryAction(null);
    try {
      onChanged(await work());
      success?.();
    } catch (reason) {
      setActionError(`${feedbackError(reason)}. Повторите действие «${label}»; черновик и выбранные значения сохранены.`);
      setRetryAction(action);
    } finally {
      busyRef.current = false;
      setBusy(null);
    }
  };

  const sendComment = () => {
    if (!comment.text.trim()) return;
    void run("comment", "Отправить комментарий", async () => {
      const response = await apiFetch(`${base}/comments`, {
        method: "POST", body: JSON.stringify(comment),
      });
      return await response.json() as FeedbackTicket;
    }, clearComment);
  };

  const originalUrl = ticket.duplicateOf
    ? `${admin ? "/admin/tickets" : "/feedback"}?ticket=${encodeURIComponent(ticket.duplicateOf)}` : null;

  return (
    <article className="panel feedback-ticket" id={`feedback-ticket-${ticket.id}`} data-ticket-id={ticket.id}>
      <div className="feedback-ticket-heading">
        <div><code>{ticket.shortId}</code><h3>{ticket.subject}</h3></div>
        <span className={`feedback-status status-${ticket.status.toLowerCase().replace(" ", "-")}`}>{feedbackStatus(ticket.status)}</span>
      </div>
      <div className="feedback-ticket-meta">
        <span>{feedbackType(ticket.type)}</span><span>{feedbackPriority(ticket.priority)}</span><span>Автор: {ticket.author}</span>
        <span>Обновлён: {feedbackDate(ticket.updatedAt)}</span>
        {ticket.resolution && <span>Результат: {feedbackResolution(ticket.resolution)}</span>}
      </div>
      <p className="feedback-description">{ticket.description}</p>
      {ticket.duplicateOf && <div className="feedback-duplicate">
        Исходный тикет: <a href={originalUrl || "#"} onClick={event => {
          event.preventDefault();
          if (originalUrl) onNavigate(originalUrl);
        }}><code>{ticket.duplicateOf}</code> — {ticket.duplicateSubject || "Тикет"}</a>
      </div>}

      <section className="feedback-thread" aria-label={`Обсуждение ${ticket.shortId}`}>
        <h4>Обсуждение</h4>
        {ticket.entries.map(entry => <div className={`feedback-entry feedback-entry-${entry.role.toLowerCase()}`} key={entry.id}>
          <div className="feedback-entry-meta">
            <strong>{entry.role === "SYSTEM" ? "Система" : entry.author || entry.role}</strong>
            <span>{entry.role === "SYSTEM" ? "Системное событие" : entry.role === "ADMIN" ? "Администратор" : "Пользователь"}</span>
            <time dateTime={entry.createdAt}>{feedbackDate(entry.createdAt)}</time>
          </div>
          <p>{entry.text}</p>
        </div>)}
      </section>

      <div className="feedback-comment">
        <label htmlFor={`comment-${ticket.id}`}>Комментарий</label>
        <textarea id={`comment-${ticket.id}`} value={comment.text} maxLength={8000} rows={4}
          placeholder="Напишите комментарий или сообщите, сохраняется ли проблема"
          onChange={event => setComment({ text: event.target.value, requestId: feedbackRequestId() })} />
        <div className="feedback-actions">
          <button disabled={busy !== null || !comment.text.trim()} onClick={sendComment}>
            {busy === "comment" ? "Отправка…" : retryAction === "comment" ? "Повторить отправку" : "Отправить комментарий"}
          </button>
          {!admin && ticket.status === "Testing" && <button className="secondary" disabled={busy !== null}
            onClick={() => void run("confirm", "Подтвердить исправление", async () => {
              const response = await apiFetch(`${base}/confirm`, { method: "POST" });
              return await response.json() as FeedbackTicket;
            })}>Подтвердить исправление</button>}
          {!admin && ticket.status === "Done" && (ticket.resolution === "Completed" || ticket.resolution === "Answered")
            && <button className="secondary" disabled={busy !== null} onClick={() => setReopenVisible(current => !current)}>
              {reopenVisible ? "Отменить переоткрытие" : "Переоткрыть тикет"}
            </button>}
        </div>
      </div>

      {!admin && reopenVisible && ticket.status === "Done"
        && (ticket.resolution === "Completed" || ticket.resolution === "Answered") && <div className="feedback-reopen">
        <label htmlFor={`reopen-${ticket.id}`}>Почему вы переоткрываете тикет?</label>
        <textarea id={`reopen-${ticket.id}`} value={reopen.text} maxLength={8000} rows={3} required
          onChange={event => setReopen({ text: event.target.value, requestId: feedbackRequestId() })} />
        <button className="secondary" disabled={busy !== null || !reopen.text.trim()}
          onClick={() => void run("reopen", "Переоткрыть тикет", async () => {
            const response = await apiFetch(`${base}/reopen`, { method: "POST", body: JSON.stringify(reopen) });
            return await response.json() as FeedbackTicket;
          }, () => {
            clearReopen();
            setReopenVisible(false);
          })}>{busy === "reopen" ? "Переоткрытие…" : "Переоткрыть с указанием причины"}</button>
      </div>}

      {admin && <section className="feedback-admin-actions" aria-label={`Действия администратора ${ticket.shortId}`}>
        <div className="feedback-metadata-editor">
          <label>Тип<select value={type} onChange={event => setType(event.target.value as FeedbackTicket["type"])}>
            {types.map(value => <option key={value} value={value}>{feedbackType(value)}</option>)}
          </select></label>
          <label>Приоритет<select value={priority} onChange={event => setPriority(event.target.value as FeedbackTicket["priority"])}>
            {priorities.map(value => <option key={value} value={value}>{feedbackPriority(value)}</option>)}
          </select></label>
          <button className="secondary" disabled={busy !== null || type === ticket.type && priority === ticket.priority}
            onClick={() => void run("metadata", "Сохранить тип и приоритет", async () => {
              const response = await apiFetch(base, { method: "PATCH", body: JSON.stringify({ type, priority }) });
              return await response.json() as FeedbackTicket;
            })}>Сохранить тип и приоритет</button>
        </div>
        <div className="feedback-actions">
          {(ticket.status === "Accepted" || ticket.status === "Testing") && <button className="secondary" disabled={busy !== null}
            onClick={() => void run("in-progress", "Взять в работу", async () => {
              const response = await apiFetch(`${base}/in-progress`, { method: "POST" });
              return await response.json() as FeedbackTicket;
            })}>{ticket.status === "Testing" ? "Вернуть в работу" : "Взять в работу"}</button>}
          {ticket.status === "In Progress" && <button className="secondary" disabled={busy !== null}
            onClick={() => void run("testing", "Отправить исправление на проверку", async () => {
              const response = await apiFetch(`${base}/testing`, { method: "POST" });
              return await response.json() as FeedbackTicket;
            })}>Отправить исправление на проверку</button>}
        </div>
        {ticket.status !== "Done" && <div className="feedback-close-controls">
          <label>Результат закрытия<select value={resolution}
            onChange={event => setResolution(event.target.value as typeof resolution)}>
            <option value="Answered">Отвечен</option><option value="Rejected">Отклонён</option><option value="Duplicate">Дубликат</option>
          </select></label>
          {resolution === "Duplicate" && <label>ID исходного тикета<input value={duplicateOf} required
            placeholder="Полный UUID или уникальный префикс" onChange={event => setDuplicateOf(event.target.value)} /></label>}
          <button className="danger" disabled={busy !== null || resolution === "Duplicate" && !duplicateOf.trim()}
            onClick={() => void run("close", "Закрыть тикет", async () => {
              const response = await apiFetch(`${base}/close`, {
                method: "POST", body: JSON.stringify({ resolution, duplicateOf: resolution === "Duplicate" ? duplicateOf.trim() : null }),
              });
              return await response.json() as FeedbackTicket;
            })}>Закрыть тикет</button>
        </div>}
      </section>}
      {actionError && <p className="feedback-action-error" role="alert">{actionError}</p>}
    </article>
  );
}
