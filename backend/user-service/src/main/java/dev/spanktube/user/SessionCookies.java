package dev.spanktube.user;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
class SessionCookies {

    static final String NAME = "spanktube_session";
    private static final String PATH = "/api/v1/auth";
    private final AuthProperties properties;

    SessionCookies(AuthProperties properties) {
        this.properties = properties;
    }

    void set(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(NAME, refreshToken)
                .httpOnly(true)
                .sameSite("Lax")
                .path(PATH)
                .maxAge(properties.refreshTokenTtl())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    void clear(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .sameSite("Lax")
                .path(PATH)
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
