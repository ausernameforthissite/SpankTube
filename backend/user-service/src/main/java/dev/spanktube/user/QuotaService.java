package dev.spanktube.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class QuotaService {

    private final JdbcTemplate jdbc;
    private final QuotaProperties properties;
    private final AuthProperties authProperties;

    QuotaService(JdbcTemplate jdbc, QuotaProperties properties, AuthProperties authProperties) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.authProperties = authProperties;
    }

    UserQuota resolve(String username) {
        AuthProperties.Account account = account(username);
        if (account == null) {
            throw new IllegalArgumentException("Unknown user " + username);
        }
        Long override = jdbc.query("select quota_bytes from user_quotas where username = ?",
                rs -> rs.next() ? rs.getObject(1, Long.class) : null, username);
        long roleDefault = account.role() == AuthProperties.Role.ADMIN
                ? properties.adminDefaultBytes() : properties.viewerDefaultBytes();
        return new UserQuota(username, account.role(), override,
                override == null ? roleDefault : override, properties.lruThresholdPercent());
    }

    UserQuota update(String username, Long quotaBytes) {
        AuthProperties.Account account = account(username);
        if (account == null) {
            throw new IllegalArgumentException("Unknown user " + username);
        }
        if (quotaBytes != null && quotaBytes <= 0) {
            throw new IllegalArgumentException("quotaBytes must be positive");
        }
        if (quotaBytes == null) {
            jdbc.update("delete from user_quotas where username = ?", username);
        } else {
            jdbc.update("insert into user_quotas(username, quota_bytes) values (?, ?) "
                    + "on conflict(username) do update set quota_bytes = excluded.quota_bytes", username, quotaBytes);
        }
        return resolve(username);
    }

    private AuthProperties.Account account(String username) {
        AuthProperties.Account configured = authProperties.users().get(username);
        if (configured != null) {
            return configured;
        }
        return jdbc.query("select password_hash, role from local_users where username = ?", rs -> {
            if (!rs.next()) {
                return null;
            }
            return new AuthProperties.Account(rs.getString("password_hash"),
                    AuthProperties.Role.valueOf(rs.getString("role")));
        }, username);
    }

    record UserQuota(String username, AuthProperties.Role role, Long quotaBytes,
            long effectiveQuotaBytes, int lruThresholdPercent) {
    }
}
