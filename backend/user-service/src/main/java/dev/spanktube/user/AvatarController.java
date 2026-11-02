package dev.spanktube.user;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AvatarController {

    private final JdbcTemplate jdbc;

    AvatarController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PutMapping("/api/v1/users/me/avatar")
    ResponseEntity<Void> upload(@AuthenticationPrincipal Jwt jwt, @RequestBody byte[] data) throws IOException {
        AvatarImage.ImageInfo image = AvatarImage.read(data);
        jdbc.update("insert into user_settings(username, avatar, avatar_content_type) values (?, ?, ?) "
                        + "on conflict(username) do update set avatar = excluded.avatar, "
                        + "avatar_content_type = excluded.avatar_content_type",
                jwt.getSubject(), data, image.contentType());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/users/me/avatar")
    ResponseEntity<byte[]> avatar(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.query("select avatar, avatar_content_type from user_settings where username = ?", rs -> {
            if (!rs.next() || rs.getBytes("avatar") == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Avatar not found");
            }
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(rs.getString("avatar_content_type")))
                    .body(rs.getBytes("avatar"));
        }, jwt.getSubject());
    }

    @DeleteMapping("/api/v1/users/me/avatar")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt) {
        jdbc.update("update user_settings set avatar = null, avatar_content_type = null where username = ?", jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}
