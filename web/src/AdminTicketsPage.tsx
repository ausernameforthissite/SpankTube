import { useCallback, useEffect, useState } from "react";
import { feedbackError, FeedbackTicketCard } from "./FeedbackTicketCard";
import { feedbackDate, feedbackPriority, feedbackStatus, feedbackType } from "./feedbackText";
import type { ApiFetch, FeedbackTicket } from "./types";

export function AdminTicketsPage({ apiFetch, username, selectedTicketId, revision, onNavigate }: {
  apiFetch: ApiFetch;
  username: string;
  selectedTicketId: string | null;
  revision: number;
  onNavigate: (url: string) => void;
}) {
  const [tickets, setTickets] = useState<FeedbackTicket[]>([]);
  const [selected, setSelected] = useState<FeedbackTicket | null>(null);
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const [applied, setApplied] = useState({ query: "", status: "" });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    const params = new URLSearchParams();
    if (applied.query) params.set("query", applied.query);
    if (applied.status) params.set("status", applied.status);
    try {
      const [listResponse, selectedResponse] = await Promise.all([
        apiFetch(`/api/v1/admin/tickets?${params.toString()}`),
        selectedTicketId ? apiFetch(`/api/v1/admin/tickets/${encodeURIComponent(selectedTicketId)}`) : Promise.resolve(null),
      ]);
      setTickets(await listResponse.json() as FeedbackTicket[]);
      setSelected(selectedResponse ? await selectedResponse.json() as FeedbackTicket : null);
    } catch (reason) {
      setError(`${feedbackError(reason)}. Повторите действие «Обновить»; черновики комментариев сохранены.`);
    } finally {
      setLoading(false);
    }
  }, [apiFetch, applied, selectedTicketId]);

  useEffect(() => {
    void load();
  }, [load, revision]);

  const update = (updated: FeedbackTicket) => {
    setSelected(current => current?.id === updated.id ? updated : current);
    setTickets(current => current.map(ticket => ticket.id === updated.id ? updated : ticket));
  };

  return (
    <div className="feedback-page">
      <div className="feedback-page-heading"><h2>Тикеты</h2>
        <button className="secondary" disabled={loading} onClick={() => void load()}>{loading ? "Обновление…" : "Обновить"}</button>
      </div>
      {error && <p className="feedback-action-error" role="alert">{error}</p>}

      {selectedTicketId ? <>
        <button className="link feedback-back" onClick={() => onNavigate("/admin/tickets")}>← К очереди тикетов</button>
        {selected && <FeedbackTicketCard ticket={selected} admin username={username} apiFetch={apiFetch}
          onChanged={update} onNavigate={onNavigate} />}
      </> : <>
        <section className="panel feedback-queue-filter">
          <form onSubmit={event => { event.preventDefault(); setApplied({ query: query.trim(), status }); }}>
            <label>Поиск по теме, автору или ID<input value={query}
              onChange={event => setQuery(event.target.value)} /></label>
            <label>Статус<select value={status} onChange={event => setStatus(event.target.value)}>
              <option value="">Все статусы</option>
              {(["Accepted", "In Progress", "Testing", "Done"] as const).map(value =>
                <option key={value} value={value}>{feedbackStatus(value)}</option>)}
            </select></label>
            <button type="submit" disabled={loading}>Найти</button>
          </form>
        </section>
        <section className="panel feedback-queue" aria-label="Очередь тикетов">
          {!loading && tickets.length === 0 && <p className="muted">Тикеты по этому фильтру не найдены.</p>}
          {tickets.map(ticket => <button className="feedback-queue-row" key={ticket.id}
            onClick={() => onNavigate(`/admin/tickets?ticket=${encodeURIComponent(ticket.id)}`)}>
            <span className="feedback-queue-subject"><code>{ticket.shortId}</code><strong>{ticket.subject}</strong></span>
            <span>{ticket.author}</span><span>{feedbackStatus(ticket.status)}</span>
            <span>{feedbackType(ticket.type)}</span><span>{feedbackPriority(ticket.priority)}</span>
            <time dateTime={ticket.updatedAt}>{feedbackDate(ticket.updatedAt)}</time>
          </button>)}
        </section>
      </>}
    </div>
  );
}
