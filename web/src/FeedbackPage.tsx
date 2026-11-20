import { useCallback, useEffect, useRef, useState } from "react";
import { feedbackError, FeedbackTicketCard } from "./FeedbackTicketCard";
import { useFeedbackDraft } from "./feedbackDrafts";
import { feedbackPriority, feedbackType } from "./feedbackText";
import type { ApiFetch, FeedbackTicket } from "./types";

type NewTicketDraft = {
  subject: string;
  description: string;
  type: FeedbackTicket["type"];
  priority: FeedbackTicket["priority"];
};

const emptyDraft: NewTicketDraft = { subject: "", description: "", type: "Question", priority: "Major" };
const types: FeedbackTicket["type"][] = ["Question", "Bug", "Feature", "Improvement"];
const priorities: FeedbackTicket["priority"][] = ["Minor", "Major", "Critical", "Blocker"];

export function FeedbackPage({ apiFetch, username, selectedTicketId, revision, onNavigate }: {
  apiFetch: ApiFetch;
  username: string;
  selectedTicketId: string | null;
  revision: number;
  onNavigate: (url: string) => void;
}) {
  const [tickets, setTickets] = useState<FeedbackTicket[]>([]);
  const [draft, setDraft, clearDraft] = useFeedbackDraft<NewTicketDraft>(`spanktube.feedback.${username}.new`, emptyDraft);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const submittingRef = useRef(false);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const [listResponse, selectedResponse] = await Promise.all([
        apiFetch("/api/v1/feedback/tickets"),
        selectedTicketId ? apiFetch(`/api/v1/feedback/tickets/${encodeURIComponent(selectedTicketId)}`) : Promise.resolve(null),
      ]);
      const list = await listResponse.json() as FeedbackTicket[];
      const selected = selectedResponse ? await selectedResponse.json() as FeedbackTicket : null;
      setTickets(selected && !list.some(ticket => ticket.id === selected.id) ? [selected, ...list] : list);
    } catch (reason) {
      setLoadError(`${feedbackError(reason)}. Повторите действие «Обновить»; черновики сохранены.`);
    } finally {
      setLoading(false);
    }
  }, [apiFetch, selectedTicketId]);

  useEffect(() => {
    void load();
  }, [load, revision]);

  useEffect(() => {
    if (selectedTicketId && tickets.some(ticket => ticket.id === selectedTicketId)) {
      document.getElementById(`feedback-ticket-${selectedTicketId}`)?.scrollIntoView({ block: "start" });
    }
  }, [selectedTicketId, tickets]);

  const update = (updated: FeedbackTicket) => setTickets(current => [updated, ...current.filter(ticket => ticket.id !== updated.id)]
    .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt)));

  const submit = async () => {
    if (submittingRef.current || !draft.subject.trim() || !draft.description.trim()) return;
    submittingRef.current = true;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const response = await apiFetch("/api/v1/feedback/tickets", {
        method: "POST", body: JSON.stringify({ ...draft, subject: draft.subject.trim(), description: draft.description.trim() }),
      });
      const created = await response.json() as FeedbackTicket;
      update(created);
      clearDraft();
    } catch (reason) {
      setSubmitError(`${feedbackError(reason)}. Повторите действие «Создать тикет»; введённые данные сохранены.`);
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  return (
    <div className="feedback-page">
      <div className="feedback-page-heading"><h2>Обратная связь</h2>
        <button className="secondary" disabled={loading} onClick={() => void load()}>{loading ? "Обновление…" : "Обновить"}</button>
      </div>
      {loadError && <p className="feedback-action-error" role="alert">{loadError}</p>}

      <section className="panel feedback-new-ticket">
        <h3>Новый тикет</h3>
        <form onSubmit={event => { event.preventDefault(); void submit(); }}>
          <label>Тема<input value={draft.subject} required maxLength={160}
            onChange={event => setDraft({ ...draft, subject: event.target.value })} /></label>
          <label>Описание<textarea value={draft.description} required maxLength={8000} rows={6}
            placeholder="Что произошло, что вы ожидали и что получили в результате?"
            onChange={event => setDraft({ ...draft, description: event.target.value })} /></label>
          <div className="feedback-form-options">
            <label>Тип<select value={draft.type} onChange={event => setDraft({ ...draft, type: event.target.value as FeedbackTicket["type"] })}>
              {types.map(type => <option key={type} value={type}>{feedbackType(type)}</option>)}
            </select></label>
            <label>Приоритет<select value={draft.priority}
              onChange={event => setDraft({ ...draft, priority: event.target.value as FeedbackTicket["priority"] })}>
              {priorities.map(priority => <option key={priority} value={priority}>{feedbackPriority(priority)}</option>)}
            </select></label>
          </div>
          <button type="submit" disabled={submitting || !draft.subject.trim() || !draft.description.trim()}>
            {submitting ? "Создание…" : submitError ? "Повторить создание тикета" : "Создать тикет"}
          </button>
        </form>
        {submitError && <p className="feedback-action-error" role="alert">{submitError}</p>}
      </section>

      <section className="feedback-my-tickets">
        <h3>Мои тикеты</h3>
        {!loading && tickets.length === 0 && !loadError && <p className="muted">У вас пока нет тикетов.</p>}
        {tickets.map(ticket => <FeedbackTicketCard key={ticket.id} ticket={ticket} admin={false} username={username}
          apiFetch={apiFetch} onChanged={update} onNavigate={onNavigate} />)}
      </section>
    </div>
  );
}
