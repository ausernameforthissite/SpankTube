package dev.spanktube.upstream;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataLogServiceTest {

    @Test
    void replaysLogsWrittenBeforeSubscription() {
        MetadataLogService service = new MetadataLogService();
        service.log("metadata-test", "Метаданные канала получены");

        List<ServerSentEvent<String>> events = service.stream("metadata-test").take(2).collectList().block();

        assertThat(events).extracting(ServerSentEvent::data)
                .containsExactly("Ожидание метаданных…", "Метаданные канала получены");
    }

    @Test
    void keepsOnlyTenLines() {
        MetadataLogService service = new MetadataLogService();
        for (int index = 0; index < 12; index++) {
            service.log("metadata-test", "line " + index);
        }

        List<ServerSentEvent<String>> events = service.stream("metadata-test").take(10).collectList().block();

        assertThat(events).hasSize(10);
        assertThat(events.getFirst().data()).isEqualTo("line 2");
        assertThat(events.getLast().data()).isEqualTo("line 11");
    }
}
