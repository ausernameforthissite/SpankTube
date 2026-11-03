package dev.spanktube.catalog;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpServerErrorException;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogErrorHandlerTest {

    @Test
    void preservesUpstreamAntibotError() {
        String body = "{\"code\":\"ANTIBOT\",\"actions\":[\"Enable the bgutil PO-token provider for yt-dlp\"]}";
        HttpServerErrorException error = HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        var response = new CatalogErrorHandler().downstream(error);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo(body);
    }
}
