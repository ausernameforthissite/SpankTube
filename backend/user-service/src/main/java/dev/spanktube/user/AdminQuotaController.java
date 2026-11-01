package dev.spanktube.user;

import java.util.Comparator;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AdminQuotaController {

    private final QuotaService quotaService;
    private final AuthProperties authProperties;
    private final CatalogUsageClient usageClient;

    AdminQuotaController(QuotaService quotaService, AuthProperties authProperties, CatalogUsageClient usageClient) {
        this.quotaService = quotaService;
        this.authProperties = authProperties;
        this.usageClient = usageClient;
    }

    @GetMapping("/internal/v1/users/{username}/quota")
    QuotaService.UserQuota quota(@PathVariable String username) {
        return quotaService.resolve(username);
    }

    @GetMapping("/api/v1/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    List<AdminUserQuota> users() {
        return authProperties.users().keySet().stream()
                .sorted(Comparator.naturalOrder())
                .map(username -> {
                    QuotaService.UserQuota quota = quotaService.resolve(username);
                    return new AdminUserQuota(quota.username(), quota.role(), quota.quotaBytes(),
                            usageClient.usedBytes(username), quota.effectiveQuotaBytes(), quota.lruThresholdPercent());
                })
                .toList();
    }

    @GetMapping("/api/v1/admin/users/{username}/quota")
    @PreAuthorize("hasRole('ADMIN')")
    AdminUserQuota userQuota(@PathVariable String username) {
        QuotaService.UserQuota quota = quotaService.resolve(username);
        return new AdminUserQuota(quota.username(), quota.role(), quota.quotaBytes(),
                usageClient.usedBytes(username), quota.effectiveQuotaBytes(), quota.lruThresholdPercent());
    }

    @PutMapping("/api/v1/admin/users/{username}/quota")
    @PreAuthorize("hasRole('ADMIN')")
    QuotaService.UserQuota updateQuota(@PathVariable String username, @RequestBody QuotaUpdate update) {
        return quotaService.update(username, update.quotaBytes());
    }

    @DeleteMapping("/api/v1/admin/users/{username}/quota")
    @PreAuthorize("hasRole('ADMIN')")
    QuotaService.UserQuota resetQuota(@PathVariable String username) {
        return quotaService.update(username, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(error.getMessage());
    }

    record QuotaUpdate(Long quotaBytes) {
    }

    record AdminUserQuota(String username, AuthProperties.Role role, Long quotaBytes,
            long usedBytes, long effectiveQuotaBytes, int lruThresholdPercent) {
    }
}
