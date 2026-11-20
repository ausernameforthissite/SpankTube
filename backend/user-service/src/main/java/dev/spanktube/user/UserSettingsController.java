package dev.spanktube.user;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
class UserSettingsController {

    private final JdbcTemplate jdbc;
    private final LocalFileAccessService localFileAccess;

    UserSettingsController(JdbcTemplate jdbc, LocalFileAccessService localFileAccess) {
        this.jdbc = jdbc;
        this.localFileAccess = localFileAccess;
    }

    @GetMapping("/api/v1/users/me/settings")
    Settings settings(@AuthenticationPrincipal Jwt jwt) {
        return current(jwt.getSubject());
    }

    @PatchMapping("/api/v1/users/me/settings")
    @Transactional
    public Settings update(@AuthenticationPrincipal Jwt jwt, @RequestBody SettingsPatch patch) {
        if (patch.autoArchive() == null && patch.autoplay() == null && patch.hideTechnicalDetails() == null
                && patch.preferBestQuality() == null && patch.theme() == null && patch.archiveTargetHeight() == null
                && patch.feedbackNotifications() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No settings to update");
        }
        String username = jwt.getSubject();
        if (patch.autoplay() != null) {
            jdbc.update("insert into user_settings(username, autoplay) values (?, ?) "
                    + "on conflict(username) do update set autoplay = excluded.autoplay", username, patch.autoplay());
        }
        if (patch.hideTechnicalDetails() != null) {
            jdbc.update("insert into user_settings(username, hide_technical_details) values (?, ?) "
                    + "on conflict(username) do update set hide_technical_details = excluded.hide_technical_details",
                    username, patch.hideTechnicalDetails());
        }
        if (patch.preferBestQuality() != null) {
            jdbc.update("insert into user_settings(username, prefer_best_quality) values (?, ?) "
                    + "on conflict(username) do update set prefer_best_quality = excluded.prefer_best_quality",
                    username, patch.preferBestQuality());
        }
        if (patch.autoArchive() != null) {
            jdbc.update("insert into user_settings(username, auto_archive) values (?, ?) "
                    + "on conflict(username) do update set auto_archive = excluded.auto_archive",
                    username, patch.autoArchive());
        }
        if (patch.feedbackNotifications() != null) {
            jdbc.update("insert into user_settings(username, feedback_notifications) values (?, ?) "
                    + "on conflict(username) do update set feedback_notifications = excluded.feedback_notifications",
                    username, patch.feedbackNotifications());
        }
        if (patch.archiveTargetHeight() != null) {
            JsonNode value = patch.archiveTargetHeight();
            if (value.isNull()) {
                jdbc.update("update user_settings set archive_target_height = null where username = ?", username);
            } else {
                if (!value.isIntegralNumber() || value.intValue() < 1 || value.intValue() > 10_000) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Archive target height must be between 1 and 10000");
                }
                jdbc.update("insert into user_settings(username, archive_target_height) values (?, ?) "
                        + "on conflict(username) do update set archive_target_height = excluded.archive_target_height",
                        username, value.intValue());
            }
        }
        if (patch.theme() != null) {
            if (!java.util.Set.of("system", "light", "dark").contains(patch.theme())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown theme");
            }
            jdbc.update("insert into user_settings(username, theme) values (?, ?) "
                    + "on conflict(username) do update set theme = excluded.theme", username, patch.theme());
        }
        return current(username);
    }

    @GetMapping("/internal/v1/users/{username}/settings")
    Settings internalSettings(@PathVariable String username) {
        return current(username);
    }

    private Settings current(String username) {
        boolean filesAccess = localFileAccess.access(username).enabled();
        return jdbc.query("select auto_archive, autoplay, hide_technical_details, prefer_best_quality, theme, "
                        + "archive_target_height, feedback_notifications from user_settings where username = ?",
                rs -> rs.next() ? new Settings(rs.getBoolean("auto_archive"), rs.getBoolean("autoplay"),
                        rs.getBoolean("hide_technical_details"), rs.getBoolean("prefer_best_quality"),
                        rs.getString("theme"), filesAccess, rs.getObject("archive_target_height", Integer.class),
                        rs.getBoolean("feedback_notifications"))
                        : new Settings(true, true, false, true, "system", filesAccess, null, true), username);
    }

    record Settings(boolean autoArchive, boolean autoplay, boolean hideTechnicalDetails, boolean preferBestQuality,
            String theme, boolean localFilesAccess, Integer archiveTargetHeight, boolean feedbackNotifications) {
    }

    record SettingsPatch(Boolean autoArchive, Boolean autoplay, Boolean hideTechnicalDetails,
            Boolean preferBestQuality, String theme, JsonNode archiveTargetHeight, Boolean feedbackNotifications) {
    }
}
