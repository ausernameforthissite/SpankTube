package dev.spanktube.user;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tickets")
@PreAuthorize("hasRole('ADMIN')")
class FeedbackAdminController {

    private final FeedbackService feedback;

    FeedbackAdminController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    @GetMapping
    List<FeedbackModels.Ticket> list(@RequestParam(required = false) String query,
            @RequestParam(required = false) String status) {
        return feedback.adminList(query, status);
    }

    @GetMapping("/{id}")
    FeedbackModels.Ticket get(@PathVariable UUID id) {
        return feedback.ticket(id, null, true);
    }

    @PostMapping("/{id}/comments")
    FeedbackModels.Ticket comment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody FeedbackModels.TextRequest request) {
        return feedback.comment(id, jwt.getSubject(), true, request.text(), request.requestId());
    }

    @PostMapping("/{id}/in-progress")
    FeedbackModels.Ticket inProgress(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return feedback.inProgress(id, jwt.getSubject());
    }

    @PostMapping("/{id}/testing")
    FeedbackModels.Ticket testing(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return feedback.testing(id, jwt.getSubject());
    }

    @PostMapping("/{id}/close")
    FeedbackModels.Ticket close(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody FeedbackModels.CloseRequest request) {
        return feedback.close(id, jwt.getSubject(), request);
    }

    @PatchMapping("/{id}")
    FeedbackModels.Ticket changeTypeAndPriority(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody FeedbackModels.TicketTypeRequest request) {
        return feedback.changeTypeAndPriority(id, jwt.getSubject(), request);
    }
}
