package dev.spanktube.user;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class FeedbackModels {

    private FeedbackModels() {
    }

    record Ticket(UUID id, String shortId, String subject, String description, String author,
            String type, String priority, String status, String resolution,
            UUID duplicateOf, String duplicateSubject, Instant createdAt, Instant updatedAt,
            List<Entry> entries) {
        Ticket {
            entries = List.copyOf(entries);
        }
    }

    record Entry(UUID id, String author, String role, String kind, String text, Instant createdAt) {
    }

    record CreateTicket(String subject, String description, String type, String priority) {
    }

    record TextRequest(String text, UUID requestId) {
    }

    record CloseRequest(String resolution, String duplicateOf) {
    }

    record TicketTypeRequest(String type, String priority) {
    }
}
