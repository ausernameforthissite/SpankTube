package dev.spanktube.user;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class UserSettingsController {

    private final JdbcTemplate jdbc;

    UserSettingsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/users/me/settings")
    Settings settings(@AuthenticationPrincipal Jwt jwt) {
        return current(jwt.getSubject());
    }

    @PatchMapping("/api/v1/users/me/settings")
    @Transactional
    public Settings update(@AuthenticationPrincipal Jwt jwt, @RequestBody SettingsPatch patch) {
        if (patch.autoplay() == null && patch.hideTechnicalDetails() == null && patch.preferBestQuality() == null) {
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
        return current(username);
    }

    private Settings current(String username) {
        return jdbc.query("select autoplay, hide_technical_details, prefer_best_quality from user_settings where username = ?",
                rs -> rs.next() ? new Settings(rs.getBoolean("autoplay"), rs.getBoolean("hide_technical_details"),
                        rs.getBoolean("prefer_best_quality")) : new Settings(true, false, true), username);
    }

    record Settings(boolean autoplay, boolean hideTechnicalDetails, boolean preferBestQuality) {
    }

    record SettingsPatch(Boolean autoplay, Boolean hideTechnicalDetails, Boolean preferBestQuality) {
    }
}
