package dev.spanktube.user;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/feedback")
class FeedbackController {

    private final FeedbackService feedback;

    @PostMapping("/tickets")
    ResponseEntity<FeedbackModels.Ticket> create(@AuthenticationPrincipal Jwt jwt,
            @RequestBody FeedbackModels.CreateTicket request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(feedback.create(jwt.getSubject(), request));
    }

    @GetMapping("/tickets")
    List<FeedbackModels.Ticket> list(@AuthenticationPrincipal Jwt jwt) {
        return feedback.list(jwt.getSubject(), admin(jwt));
    }

    @GetMapping("/tickets/{id}")
    FeedbackModels.Ticket get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return feedback.ticket(id, jwt.getSubject(), admin(jwt));
    }

    @PostMapping("/tickets/{id}/comments")
    FeedbackModels.Ticket comment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody FeedbackModels.TextRequest request) {
        return feedback.comment(id, jwt.getSubject(), admin(jwt), request.text(), request.requestId());
    }

    @PostMapping("/tickets/{id}/confirm")
    FeedbackModels.Ticket confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return feedback.confirm(id, jwt.getSubject());
    }

    @PostMapping("/tickets/{id}/reopen")
    FeedbackModels.Ticket reopen(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody FeedbackModels.TextRequest request) {
        return feedback.reopen(id, jwt.getSubject(), request.text());
    }

    @GetMapping("/events")
    SseEmitter events(@AuthenticationPrincipal Jwt jwt) {
        return feedbackEvents.register(jwt.getSubject(), admin(jwt));
    }

    private final FeedbackEvents feedbackEvents;

    FeedbackController(FeedbackService feedback, FeedbackEvents feedbackEvents) {
        this.feedback = feedback;
        this.feedbackEvents = feedbackEvents;
    }

    private static boolean admin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ROLE_ADMIN");
    }
}
