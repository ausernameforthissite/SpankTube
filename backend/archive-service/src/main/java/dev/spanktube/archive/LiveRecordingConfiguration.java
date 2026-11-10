package dev.spanktube.archive;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class LiveRecordingConfiguration {

    @Bean(destroyMethod = "shutdown")
    ExecutorService liveRecordingExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "live-recording");
            thread.setDaemon(true);
            return thread;
        });
    }
}
