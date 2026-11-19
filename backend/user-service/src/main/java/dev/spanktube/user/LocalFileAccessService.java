package dev.spanktube.user;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class LocalFileAccessService {

    private final JdbcTemplate jdbc;
    private final QuotaService quotaService;
    private final AuthProperties authProperties;

    LocalFileAccessService(JdbcTemplate jdbc, QuotaService quotaService, AuthProperties authProperties) {
        this.jdbc = jdbc;
        this.quotaService = quotaService;
        this.authProperties = authProperties;
    }

    LocalFileAccess access(String username) {
        QuotaService.UserQuota user = quotaService.resolve(username);
        boolean granted = user.role() == AuthProperties.Role.ADMIN || granted(username);
        return new LocalFileAccess(username, granted);
    }

    LocalFileAccess update(String username, Boolean enabled) {
        if (enabled == null) {
            throw new IllegalArgumentException("enabled must be provided");
        }
        QuotaService.UserQuota user = quotaService.resolve(username);
        jdbc.update("insert into user_settings(username, local_files_access) values (?, ?) "
                + "on conflict(username) do update set local_files_access = excluded.local_files_access",
                username, enabled);
        return new LocalFileAccess(username, user.role() == AuthProperties.Role.ADMIN || enabled);
    }

    List<String> knownUsernames() {
        return Stream.concat(authProperties.users().keySet().stream(),
                        jdbc.queryForList("select username from local_users", String.class).stream())
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private boolean granted(String username) {
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "select local_files_access from user_settings where username = ?", Boolean.class, username));
        } catch (EmptyResultDataAccessException error) {
            return false;
        }
    }

    record LocalFileAccess(String username, boolean enabled) {
    }
}
