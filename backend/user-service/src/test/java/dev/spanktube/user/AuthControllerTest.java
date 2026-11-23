package dev.spanktube.user;

import java.time.Duration;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private final AuthService auth = mock(AuthService.class);
    private final SessionCookies cookies = new SessionCookies(new AuthProperties("spanktube", null, null,
            Duration.ofMinutes(15), Duration.ofDays(365), Map.of()));
    private final AuthController controller = new AuthController(auth, cookies);

    @Test
    void refreshAcceptsPersistentCookieAndRotatesIt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SessionCookies.NAME, "cookie-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthService.TokenPair tokens = new AuthService.TokenPair("access", "Bearer", 900,
                "new-refresh", 31_536_000, AuthProperties.Role.VIEWER);
        when(auth.refresh("cookie-token")).thenReturn(tokens);

        assertThat(controller.refresh(null, request, response)).isSameAs(tokens);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("spanktube_session=new-refresh", "Max-Age=31536000");
    }

    @Test
    void activityRenewsCookieAndLogoutRevokesSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SessionCookies.NAME, "cookie-token"));
        MockHttpServletResponse activityResponse = new MockHttpServletResponse();
        MockHttpServletResponse logoutResponse = new MockHttpServletResponse();

        assertThat(controller.activity(null, request, activityResponse).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.logout(null, request, logoutResponse).getStatusCode().is2xxSuccessful()).isTrue();

        verify(auth).activity("cookie-token");
        verify(auth).logout("cookie-token");
        assertThat(activityResponse.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=31536000");
        assertThat(logoutResponse.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }
}
