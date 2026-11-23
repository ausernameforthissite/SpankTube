package dev.spanktube.user;

import java.time.Duration;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCookiesTest {

    private final SessionCookies cookies = new SessionCookies(new AuthProperties("spanktube", null, null,
            Duration.ofMinutes(15), Duration.ofDays(365), Map.of()));

    @Test
    void createsPersistentHttpOnlyCookieForOneYear() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookies.set(response, "refresh-token");

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("spanktube_session=refresh-token", "Path=/api/v1/auth", "Max-Age=31536000",
                        "HttpOnly", "SameSite=Lax")
                .doesNotContain("Secure");
    }

    @Test
    void readsCookieAndClearsItOnLogout() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SessionCookies.NAME, "refresh-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(cookies.read(request)).isEqualTo("refresh-token");
        cookies.clear(response);
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0", "Path=/api/v1/auth");
    }
}
