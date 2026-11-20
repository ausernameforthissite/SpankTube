package dev.spanktube.user;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedbackServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final FeedbackEvents events = mock(FeedbackEvents.class);
    private final FeedbackService service = new FeedbackService(jdbc, events);

    @Test
    void ticketWithoutAccessIsNotExposed() {
        UUID id = UUID.randomUUID();
        row(id, "Accepted", null, null);
        access(id, "user", false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.ticket(id, "user", false));

        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void onlyTestingTicketCanBeConfirmed() {
        UUID id = UUID.randomUUID();
        row(id, "Accepted", null, null);
        access(id, "user", true);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.confirm(id, "user"));

        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(jdbc, never()).update(contains("resolution = 'Completed'"), eq(id));
    }

    @Test
    void authorizedUserConfirmsTestingTicket() {
        UUID id = UUID.randomUUID();
        row(id, "Testing", null, null);
        access(id, "user", true);
        entries(id);

        service.confirm(id, "user");

        verify(jdbc).update(contains("resolution = 'Completed'"), eq(id));
        verify(jdbc).update(contains("insert into feedback_ticket_entries"), any(UUID.class), eq(id), eq("user"),
                eq("USER"), eq("SYSTEM"), eq("Исправление подтверждено; тикет завершён"));
        verify(events).publish(id);
    }

    @Test
    void rejectedTicketCannotBeReopenedAndReasonIsRequired() {
        UUID id = UUID.randomUUID();
        row(id, "Done", "Rejected", null);
        access(id, "user", true);

        assertThat(assertThrows(ResponseStatusException.class,
                () -> service.reopen(id, "user", "again")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(assertThrows(ResponseStatusException.class,
                () -> service.reopen(id, "user", " ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void duplicateCannotReferenceItself() {
        UUID id = UUID.randomUUID();
        row(id, "Accepted", null, null);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.close(id, "admin", new FeedbackModels.CloseRequest("Duplicate", id.toString())));

        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(jdbc, never()).update(contains("resolution = ?"), any(), any(), any());
    }

    @Test
    void closingDuplicateGrantsAllParticipantsAccessToOriginal() {
        UUID id = UUID.randomUUID();
        UUID original = UUID.randomUUID();
        row(id, "Accepted", null, null);
        row(original, "In Progress", null, null);
        entries(id);
        entries(original);
        when(jdbc.queryForList(contains("select username from feedback_ticket_access"), eq(String.class), eq(id)))
                .thenReturn(List.of("author"));
        when(jdbc.queryForList(contains("select distinct author_username"), eq(String.class), eq(id)))
                .thenReturn(List.of("participant"));

        service.close(id, "admin", new FeedbackModels.CloseRequest("Duplicate", original.toString()));

        verify(jdbc).update(contains("duplicate_of = cast(? as uuid)"), eq("Duplicate"), eq(original), eq(id));
        verify(jdbc).update(contains("insert into feedback_ticket_access"), eq(original), eq("author"));
        verify(jdbc).update(contains("insert into feedback_ticket_access"), eq(original), eq("participant"));
        verify(events).publish(id);
        verify(events).publish(original);
    }

    @Test
    void retryingCommittedCommentDoesNotPublishAnotherUpdate() {
        UUID id = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        row(id, "Accepted", null, null);
        access(id, "user", true);
        entries(id);
        when(jdbc.update(contains("on conflict do nothing"), any(UUID.class), eq(id), eq("user"), eq("USER"),
                eq("same text"), eq(requestId))).thenReturn(0);
        existingComment(requestId, id, "user", "same text");

        service.comment(id, "user", false, "same text", requestId);

        verify(events, never()).publish(id);
        verify(jdbc, never()).update(contains("set updated_at = now()"), eq(id));
    }

    private void row(UUID id, String status, String resolution, UUID duplicateOf) {
        doAnswer(invocation -> {
            ResultSetExtractor<Object> extractor = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getObject("id", UUID.class)).thenReturn(id);
            when(rs.getString("subject")).thenReturn("Subject");
            when(rs.getString("description")).thenReturn("Description");
            when(rs.getString("author_username")).thenReturn("author");
            when(rs.getString("type")).thenReturn("Bug");
            when(rs.getString("priority")).thenReturn("Major");
            when(rs.getString("status")).thenReturn(status);
            when(rs.getString("resolution")).thenReturn(resolution);
            when(rs.getObject("duplicate_of", UUID.class)).thenReturn(duplicateOf);
            when(rs.getString("duplicate_subject")).thenReturn(null);
            when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
            when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.now()));
            return extractor.extractData(rs);
        }).when(jdbc).query(contains("left join feedback_tickets original"), any(ResultSetExtractor.class), eq(id));
    }

    private void existingComment(UUID requestId, UUID ticketId, String author, String text) {
        doAnswer(invocation -> {
            ResultSetExtractor<Object> extractor = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getObject("ticket_id", UUID.class)).thenReturn(ticketId);
            when(rs.getString("author_username")).thenReturn(author);
            when(rs.getString("text")).thenReturn(text);
            return extractor.extractData(rs);
        }).when(jdbc).query(contains("where client_request_id = ?"), any(ResultSetExtractor.class), eq(requestId));
    }

    private void access(UUID id, String username, boolean allowed) {
        lenient().when(jdbc.queryForObject(contains("from feedback_ticket_access where"), eq(Integer.class), eq(id), eq(username)))
                .thenReturn(allowed ? 1 : 0);
    }

    private void entries(UUID id) {
        lenient().when(jdbc.query(contains("where ticket_id = ? order by created_at"), any(RowMapper.class), eq(id)))
                .thenReturn(List.of());
    }
}
