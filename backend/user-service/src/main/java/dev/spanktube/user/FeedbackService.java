package dev.spanktube.user;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
class FeedbackService {

    private static final Set<String> TYPES = Set.of("Question", "Bug", "Feature", "Improvement");
    private static final Set<String> PRIORITIES = Set.of("Minor", "Major", "Critical", "Blocker");
    private static final Set<String> RESOLUTIONS = Set.of("Answered", "Rejected", "Duplicate");

    private final JdbcTemplate jdbc;
    private final FeedbackEvents events;

    FeedbackService(JdbcTemplate jdbc, FeedbackEvents events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public FeedbackModels.Ticket create(String username, FeedbackModels.CreateTicket request) {
        String subject = request == null ? null : text(request.subject(), "Subject", 160);
        String description = request == null ? null : text(request.description(), "Description", 8000);
        String type = request == null || request.type() == null || request.type().isBlank() ? "Question" : request.type();
        String priority = request == null || request.priority() == null || request.priority().isBlank()
                ? "Major" : request.priority();
        if (!TYPES.contains(type)) throw badRequest("Unknown ticket type");
        if (!PRIORITIES.contains(priority)) throw badRequest("Unknown ticket priority");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into feedback_tickets(id, author_username, subject, description, type, priority, status, "
                        + "created_at, updated_at) values (?, ?, ?, ?, ?, ?, 'Accepted', ?, ?)",
                id, username, subject, description, type, priority, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into feedback_ticket_access(ticket_id, username) values (?, ?)", id, username);
        entry(id, null, "SYSTEM", "SYSTEM", "Тикет принят");
        updated(id);
        return ticket(id, username, false);
    }

    List<FeedbackModels.Ticket> list(String username, boolean admin) {
        List<UUID> ids = jdbc.queryForList("select t.id from feedback_tickets t "
                + "where (? or exists (select 1 from feedback_ticket_access a where a.ticket_id = t.id and a.username = ?)) "
                + "order by t.updated_at desc", UUID.class, admin, username);
        return ids.stream().map(id -> ticket(id, username, admin)).toList();
    }

    List<FeedbackModels.Ticket> adminList(String query, String status) {
        String normalized = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        List<UUID> ids = jdbc.queryForList("select t.id from feedback_tickets t where "
                + "(? = '' or lower(t.subject) like '%' || ? || '%' or lower(t.author_username) like '%' || ? || '%' "
                + "or cast(t.id as text) like '%' || ? || '%') "
                + "and (? = '' or t.status = ?) order by t.updated_at desc", UUID.class,
                normalized, normalized, normalized, normalized,
                status == null ? "" : status, status == null ? "" : status);
        return ids.stream().map(id -> ticket(id, null, true)).toList();
    }

    FeedbackModels.Ticket ticket(UUID id, String username, boolean admin) {
        TicketRow row = row(id);
        if (!admin && jdbc.queryForObject("select count(*) from feedback_ticket_access where ticket_id = ? and username = ?",
                Integer.class, id, username) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found");
        }
        List<FeedbackModels.Entry> entries = jdbc.query("select * from feedback_ticket_entries "
                        + "where ticket_id = ? order by created_at, id", (rs, rowNumber) -> new FeedbackModels.Entry(
                        rs.getObject("id", UUID.class), rs.getString("author_username"), rs.getString("author_role"),
                        rs.getString("kind"), rs.getString("text"), rs.getTimestamp("created_at").toInstant()), id);
        return row.toTicket(entries);
    }

    @Transactional
    public FeedbackModels.Ticket comment(UUID id, String username, boolean admin, String comment, UUID requestId) {
        String text = text(comment, "Comment", 8000);
        ticket(id, username, admin);
        UUID clientRequestId = requestId == null ? UUID.randomUUID() : requestId;
        int inserted = jdbc.update("insert into feedback_ticket_entries(id, ticket_id, author_username, author_role, kind, "
                        + "text, client_request_id) values (?, ?, ?, ?, 'COMMENT', ?, ?) on conflict do nothing",
                UUID.randomUUID(), id, username, admin ? "ADMIN" : "USER", text, clientRequestId);
        if (inserted == 0) {
            ExistingComment existing = jdbc.query("select ticket_id, author_username, text from feedback_ticket_entries "
                            + "where client_request_id = ?",
                    rs -> rs.next() ? new ExistingComment(rs.getObject("ticket_id", UUID.class),
                            rs.getString("author_username"), rs.getString("text")) : null, clientRequestId);
            if (existing == null || !id.equals(existing.ticketId()) || !text.equals(existing.text())
                    || !username.equals(existing.author())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Comment request ID was already used");
            }
            return ticket(id, username, admin);
        }
        touch(id);
        updated(id);
        return ticket(id, username, admin);
    }

    @Transactional
    public FeedbackModels.Ticket inProgress(UUID id, String admin) {
        TicketRow row = row(id);
        if (!Set.of("Accepted", "Testing").contains(row.status())) throw invalidTransition(row);
        jdbc.update("update feedback_tickets set status = 'In Progress', updated_at = now() where id = ?", id);
        entry(id, admin, "ADMIN", "SYSTEM", "Тикет переведён в статус «В работе»");
        updated(id);
        return ticket(id, null, true);
    }

    @Transactional
    public FeedbackModels.Ticket testing(UUID id, String admin) {
        TicketRow row = row(id);
        if (!"In Progress".equals(row.status())) throw invalidTransition(row);
        jdbc.update("update feedback_tickets set status = 'Testing', updated_at = now() where id = ?", id);
        entry(id, admin, "ADMIN", "SYSTEM", "Исправление отправлено на проверку; тикет переведён в статус «Проверка»");
        updated(id);
        return ticket(id, null, true);
    }

    @Transactional
    public FeedbackModels.Ticket confirm(UUID id, String username) {
        TicketRow row = row(id);
        requireAccess(id, username);
        if (!"Testing".equals(row.status())) throw invalidTransition(row);
        jdbc.update("update feedback_tickets set status = 'Done', resolution = 'Completed', updated_at = now() "
                + "where id = ?", id);
        entry(id, username, "USER", "SYSTEM", "Исправление подтверждено; тикет завершён");
        updated(id);
        return ticket(id, username, false);
    }

    @Transactional
    public FeedbackModels.Ticket reopen(UUID id, String username, String reason) {
        String text = text(reason, "Reopen reason", 8000);
        TicketRow row = row(id);
        requireAccess(id, username);
        if (!"Done".equals(row.status()) || !Set.of("Completed", "Answered").contains(row.resolution())) {
            throw invalidTransition(row);
        }
        jdbc.update("update feedback_tickets set status = 'Accepted', resolution = null, updated_at = now() where id = ?", id);
        entry(id, username, "USER", "REOPEN", "Тикет переоткрыт: " + text);
        updated(id);
        return ticket(id, username, false);
    }

    @Transactional
    public FeedbackModels.Ticket close(UUID id, String admin, FeedbackModels.CloseRequest request) {
        TicketRow row = row(id);
        if ("Done".equals(row.status())) throw invalidTransition(row);
        String resolution = request == null ? null : request.resolution();
        if (!RESOLUTIONS.contains(resolution)) throw badRequest("Unknown closing resolution");
        UUID original = null;
        if ("Duplicate".equals(resolution)) {
            if (request.duplicateOf() == null || request.duplicateOf().isBlank()) {
                throw badRequest("Original ticket ID is required for duplicate resolution");
            }
            original = reference(request.duplicateOf().trim());
            if (original.equals(id)) throw badRequest("A ticket cannot be a duplicate of itself");
            TicketRow originalRow = row(original);
            if (originalRow.duplicateOf() != null || "Duplicate".equals(originalRow.resolution())) {
                throw badRequest("Select an original ticket, not another duplicate");
            }
        }
        jdbc.update("update feedback_tickets set status = 'Done', resolution = ?, duplicate_of = cast(? as uuid), updated_at = now() "
                + "where id = ?", resolution, original, id);
        entry(id, admin, "ADMIN", "SYSTEM", original == null
                ? "Тикет закрыт с результатом «" + localized(resolution) + "»"
                : "Тикет закрыт как дубликат " + row(original).shortId());
        if (original != null) {
            Set<String> participants = participants(id);
            for (String participant : participants) {
                jdbc.update("insert into feedback_ticket_access(ticket_id, username) values (?, ?) on conflict do nothing",
                        original, participant);
            }
            entry(original, null, "SYSTEM", "SYSTEM", "Участники присоединились из тикета-дубликата " + row(id).shortId()
                    + ": " + String.join(", ", participants));
            touch(original);
            updated(original);
        }
        updated(id);
        return ticket(id, null, true);
    }

    @Transactional
    public FeedbackModels.Ticket changeTypeAndPriority(UUID id, String admin, FeedbackModels.TicketTypeRequest request) {
        if (request == null || request.type() == null && request.priority() == null) {
            throw badRequest("Type or priority is required");
        }
        if (request.type() != null && !TYPES.contains(request.type())) throw badRequest("Unknown ticket type");
        if (request.priority() != null && !PRIORITIES.contains(request.priority())) throw badRequest("Unknown ticket priority");
        TicketRow row = row(id);
        String type = request.type() == null ? row.type() : request.type();
        String priority = request.priority() == null ? row.priority() : request.priority();
        jdbc.update("update feedback_tickets set type = ?, priority = ?, updated_at = now() where id = ?",
                type, priority, id);
        entry(id, admin, "ADMIN", "SYSTEM", "Тип и приоритет изменены: " + localized(type) + "/" + localized(priority));
        updated(id);
        return ticket(id, null, true);
    }

    private Set<String> participants(UUID id) {
        Set<String> participants = new LinkedHashSet<>(jdbc.queryForList(
                "select username from feedback_ticket_access where ticket_id = ? order by username", String.class, id));
        participants.addAll(jdbc.queryForList("select distinct author_username from feedback_ticket_entries "
                + "where ticket_id = ? and author_username is not null order by author_username", String.class, id));
        return participants;
    }

    private UUID reference(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            List<UUID> matches = jdbc.queryForList("select id from feedback_tickets where cast(id as text) like ?",
                    UUID.class, value.toLowerCase(java.util.Locale.ROOT) + "%");
            if (matches.size() == 1) return matches.getFirst();
            if (matches.size() > 1) throw badRequest("Ticket ID prefix is ambiguous");
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Original ticket not found");
        }
    }

    private void requireAccess(UUID id, String username) {
        if (jdbc.queryForObject("select count(*) from feedback_ticket_access where ticket_id = ? and username = ?",
                Integer.class, id, username) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found");
        }
    }

    private TicketRow row(UUID id) {
        TicketRow row = jdbc.query("select t.*, original.subject as duplicate_subject from feedback_tickets t "
                        + "left join feedback_tickets original on original.id = t.duplicate_of where t.id = ?",
                rs -> rs.next() ? new TicketRow(rs.getObject("id", UUID.class), rs.getString("subject"),
                        rs.getString("description"), rs.getString("author_username"), rs.getString("type"),
                        rs.getString("priority"), rs.getString("status"), rs.getString("resolution"),
                        rs.getObject("duplicate_of", UUID.class), rs.getString("duplicate_subject"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()) : null, id);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found");
        return row;
    }

    private void entry(UUID ticket, String author, String role, String kind, String text) {
        jdbc.update("insert into feedback_ticket_entries(id, ticket_id, author_username, author_role, kind, text) "
                + "values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), ticket, author, role, kind, text);
    }

    private void touch(UUID id) {
        jdbc.update("update feedback_tickets set updated_at = now() where id = ?", id);
    }

    private void updated(UUID id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    events.publish(id);
                }
            });
        } else {
            events.publish(id);
        }
    }

    private static String text(String value, String field, int maximum) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > maximum) {
            throw badRequest(field + " is required and must be at most " + maximum + " characters");
        }
        return text;
    }

    private static String localized(String value) {
        return switch (value) {
            case "Question" -> "Вопрос";
            case "Bug" -> "Ошибка";
            case "Feature" -> "Новая функция";
            case "Improvement" -> "Улучшение";
            case "Minor" -> "Незначительный";
            case "Major" -> "Серьёзный";
            case "Critical" -> "Критический";
            case "Blocker" -> "Блокирующий";
            case "Completed" -> "Завершено";
            case "Answered" -> "Отвечен";
            case "Rejected" -> "Отклонён";
            case "Duplicate" -> "Дубликат";
            default -> value;
        };
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException invalidTransition(TicketRow row) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Action is not valid for status " + row.status() + " and resolution " + row.resolution());
    }

    private record ExistingComment(UUID ticketId, String author, String text) {
    }

    private record TicketRow(UUID id, String subject, String description, String author, String type, String priority,
            String status, String resolution, UUID duplicateOf, String duplicateSubject, Instant createdAt,
            Instant updatedAt) {
        String shortId() {
            return id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        }

        FeedbackModels.Ticket toTicket(List<FeedbackModels.Entry> entries) {
            return new FeedbackModels.Ticket(id, shortId(), subject, description, author, type, priority, status,
                    resolution, duplicateOf, duplicateSubject, createdAt, updatedAt, entries);
        }
    }
}
