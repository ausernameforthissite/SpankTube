package dev.spanktube.upstream;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeCookieStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void filtersNetscapeCookiesForRequest() throws Exception {
        Path file = tempDir.resolve("cookies.txt");
        Files.writeString(file, """
                # Netscape HTTP Cookie File
                #HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tsecret
                .youtube.com\tTRUE\t/\tTRUE\t0\tCONSENT\tyes
                .youtube.com\tTRUE\t/\tTRUE\t1\tEXPIRED\tno
                .google.com\tTRUE\t/\tTRUE\t0\tGOOGLE\tother
                """);
        YouTubeCookieStore store = new YouTubeCookieStore(file);

        String header = store.cookieHeader(URI.create("https://www.youtube.com/watch?v=test")).orElseThrow();

        assertThat(header).contains("SID=secret", "CONSENT=yes")
                .doesNotContain("EXPIRED", "GOOGLE");
        assertThat(store.cookieHeader(URI.create("http://www.youtube.com/"))).isEmpty();
        assertThat(store.cookieHeader(URI.create("https://googlevideo.com/"))).isEmpty();
    }
}
