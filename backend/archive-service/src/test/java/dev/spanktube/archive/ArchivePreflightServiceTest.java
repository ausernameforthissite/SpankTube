package dev.spanktube.archive;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchivePreflightServiceTest {

    @Test
    void estimatesAllPagesAndOnlyNewBytes() {
        UpstreamClient upstream = mock(UpstreamClient.class);
        CatalogClient catalog = mock(CatalogClient.class);
        when(upstream.channelVideos("channel", null)).thenReturn(new UpstreamClient.PageItems(List.of("v1"), "2"));
        when(upstream.channelVideos("channel", "2")).thenReturn(new UpstreamClient.PageItems(List.of("v2"), null));
        when(upstream.channelLiveStreams("channel", null)).thenReturn(new UpstreamClient.PageItems(
                List.of("live", "recording"), null, java.util.Map.of("live", "LIVE_STREAM", "recording", "POST_LIVE_STREAM")));
        when(upstream.channelPlaylists("channel", null)).thenReturn(new UpstreamClient.PageItems(List.of("p1"), "2"));
        when(upstream.channelPlaylists("channel", "2")).thenReturn(new UpstreamClient.PageItems(List.of("p2"), null));
        when(upstream.playlistVideos("p1", null)).thenReturn(new UpstreamClient.PageItems(
                List.of("v3", "playlist-live"), null, java.util.Map.of("playlist-live", "LIVE_STREAM")));
        when(upstream.playlistVideos("p2", null)).thenReturn(new UpstreamClient.PageItems(List.of("v1", "v4"), null));
        when(upstream.video(anyString())).thenAnswer(invocation -> download(invocation.getArgument(0)));
        when(catalog.existing("viewer", "v2")).thenReturn(Optional.of(mock(ArchiveModels.ExistingVideo.class)));
        ArchiveSpeedService speed = new ArchiveSpeedService();
        speed.record(ArchiveModels.Stage.DOWNLOADING_VIDEO, 1000, 500_000_000L);
        speed.record(ArchiveModels.Stage.DOWNLOADING_AUDIO, 1000, 1_000_000_000L);
        ArchivePreflightService service = service(upstream, catalog, speed);
        try {
            ArchivePreflightService.Estimate estimate = service.estimate(ArchiveModels.Kind.CHANNEL, "channel", "viewer")
                    .block();

            assertThat(estimate).isNotNull();
            assertThat(estimate.videoIds()).containsExactly("v1", "v2", "recording", "v3", "v4");
            assertThat(estimate.totalVideoCount()).isEqualTo(5);
            assertThat(estimate.newVideoCount()).isEqualTo(4);
            assertThat(estimate.existingVideoCount()).isEqualTo(1);
            assertThat(estimate.videoBytes()).isEqualTo(5000);
            assertThat(estimate.audioBytes()).isEqualTo(2500);
            assertThat(estimate.totalBytes()).isEqualTo(7500);
            assertThat(estimate.estimatedSeconds()).isEqualTo(5);
            assertThat(estimate.videoBps()).isEqualTo(2000);
            assertThat(estimate.audioBps()).isEqualTo(1000);
            assertThat(service.validate(estimate.estimateId(), ArchiveModels.Kind.CHANNEL, "channel", "viewer"))
                    .isEqualTo(estimate);
            assertThat(service.status("viewer", estimate.estimateId()).status()).isEqualTo("COMPLETED");
            assertThat(service.status("viewer", estimate.estimateId()).processedVideoCount()).isEqualTo(5);
            assertThatThrownBy(() -> service.validate("missing", ArchiveModels.Kind.CHANNEL, "channel", "viewer"))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void reportsPageAndVideoEventsWithSpeedAndRemainingItems() throws Exception {
        UpstreamClient upstream = mock(UpstreamClient.class);
        CatalogClient catalog = mock(CatalogClient.class);
        when(upstream.playlistVideos("playlist", null))
                .thenReturn(new UpstreamClient.PageItems(List.of("v1", "v2"), null));
        CountDownLatch blockedStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(upstream.video("v1")).thenAnswer(invocation -> {
            blockedStarted.countDown();
            release.await(10, TimeUnit.SECONDS);
            return download("v1");
        });
        when(upstream.video("v2")).thenReturn(download("v2"));
        when(catalog.existing(eq("viewer"), anyString())).thenReturn(Optional.empty());
        ArchivePreflightService service = service(upstream, catalog, new ArchiveSpeedService());
        CompletableFuture<ArchivePreflightService.Estimate> result = new CompletableFuture<>();
        List<ArchivePreflightService.ProgressEvent> events = new CopyOnWriteArrayList<>();
        try {
            service.estimate(ArchiveModels.Kind.PLAYLIST, "playlist", "viewer", "estimate-id")
                    .subscribe(result::complete, result::completeExceptionally);
            Runnable unsubscribe = service.subscribe("viewer", "estimate-id", events::add);
            try {
                assertThat(blockedStarted.await(2, TimeUnit.SECONDS)).isTrue();

                ArchivePreflightService.ProgressSnapshot progress = service.status("viewer", "estimate-id");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (progress.processedVideoCount() < 1 && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                    progress = service.status("viewer", "estimate-id");
                }
                assertThat(progress.status()).isEqualTo("ESTIMATING");
                assertThat(progress.totalVideoCount()).isEqualTo(2);
                assertThat(progress.processedVideoCount()).isEqualTo(1);
                assertThat(progress.pageNumber()).isEqualTo(1);
                assertThat(progress.pageVideoCount()).isEqualTo(2);
                assertThat(progress.remainingOnPage()).isEqualTo(1);
                assertThat(progress.videosPerSecond()).isGreaterThan(0);
                assertThat(progress.videoBytes()).isEqualTo(1250);
                assertThat(progress.audioBytes()).isEqualTo(625);
                assertThat(progress.totalBytes()).isEqualTo(1875);

                release.countDown();
                ArchivePreflightService.Estimate estimate = result.get(2, TimeUnit.SECONDS);
                assertThat(estimate.estimateId()).isEqualTo("estimate-id");
                assertThat(estimate.totalBytes()).isEqualTo(3750);
                ArchivePreflightService.ProgressSnapshot completed = service.status("viewer", "estimate-id");
                assertThat(completed.status()).isEqualTo("COMPLETED");
                assertThat(completed.processedVideoCount()).isEqualTo(2);
                assertThat(completed.remainingOnPage()).isZero();
                assertThat(completed.totalBytes()).isEqualTo(3750);
                assertThat(events).anyMatch(event -> event.name().equals("page")
                        && event.progress().discoveredVideoCount() == 2);
                assertThat(events).anyMatch(event -> event.name().equals("video")
                        && event.progress().processedVideoCount() == 1
                        && event.progress().remainingOnPage() == 1);
                assertThat(events).anyMatch(event -> event.name().equals("completed"));
                assertThatThrownBy(() -> service.status("other", "estimate-id"))
                        .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                                error -> assertThat(error.getStatusCode())
                                        .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
            } finally {
                unsubscribe.run();
            }
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void cancellationInterruptsActiveVideosAndSkipsQueuedVideos() throws Exception {
        UpstreamClient upstream = mock(UpstreamClient.class);
        CatalogClient catalog = mock(CatalogClient.class);
        when(upstream.playlistVideos("playlist", null)).thenReturn(new UpstreamClient.PageItems(
                List.of("v1", "v2", "v3", "v4", "v5", "v6"), null));
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        when(upstream.video(anyString())).thenAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return download(invocation.getArgument(0));
        });
        ArchivePreflightService service = service(upstream, catalog, new ArchiveSpeedService());
        CompletableFuture<ArchivePreflightService.Estimate> result = new CompletableFuture<>();
        List<ArchivePreflightService.ProgressEvent> events = new CopyOnWriteArrayList<>();
        try {
            service.estimate(ArchiveModels.Kind.PLAYLIST, "playlist", "viewer", "cancel-id")
                    .subscribe(result::complete, result::completeExceptionally, () -> result.complete(null));
            Runnable unsubscribe = service.subscribe("viewer", "cancel-id", events::add);
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

                service.cancel("viewer", "cancel-id");
                release.countDown();

                assertThat(result.get(2, TimeUnit.SECONDS)).isNull();
                assertThat(service.status("viewer", "cancel-id").status()).isEqualTo("CANCELLED");
                assertThat(events).anyMatch(event -> event.name().equals("cancelled"));
                verify(upstream, times(4)).video(anyString());
                verify(upstream, never()).video("v5");
                verify(upstream, never()).video("v6");
                assertThatThrownBy(() -> service.validate("cancel-id", ArchiveModels.Kind.PLAYLIST, "playlist", "viewer"))
                        .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                                error -> assertThat(error.getStatusCode())
                                        .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
            } finally {
                unsubscribe.run();
            }
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void collectsChannelPlaylistPagesInParallel() throws Exception {
        UpstreamClient upstream = mock(UpstreamClient.class);
        CatalogClient catalog = mock(CatalogClient.class);
        when(upstream.channelVideos("channel", null)).thenReturn(new UpstreamClient.PageItems(List.of(), null));
        when(upstream.channelLiveStreams("channel", null)).thenReturn(new UpstreamClient.PageItems(List.of(), null));
        when(upstream.channelPlaylists("channel", null))
                .thenReturn(new UpstreamClient.PageItems(List.of("p1", "p2"), null));
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(upstream.playlistVideos("p1", null)).thenAnswer(invocation -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return new UpstreamClient.PageItems(List.of("v1"), null);
        });
        when(upstream.playlistVideos("p2", null)).thenAnswer(invocation -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return new UpstreamClient.PageItems(List.of("v2"), null);
        });
        when(upstream.video(anyString())).thenAnswer(invocation -> download(invocation.getArgument(0)));
        when(catalog.existing(eq("viewer"), anyString())).thenReturn(Optional.empty());
        ArchivePreflightService service = service(upstream, catalog, new ArchiveSpeedService());
        CompletableFuture<ArchivePreflightService.Estimate> result = new CompletableFuture<>();
        try {
            service.estimate(ArchiveModels.Kind.CHANNEL, "channel", "viewer")
                    .subscribe(result::complete, result::completeExceptionally);
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            ArchivePreflightService.Estimate estimate = result.get(2, TimeUnit.SECONDS);
            assertThat(estimate.videoIds()).containsExactly("v1", "v2");
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void startReturnsProgressBeforeLongComputationFinishes() throws Exception {
        UpstreamClient upstream = mock(UpstreamClient.class);
        CatalogClient catalog = mock(CatalogClient.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(upstream.playlistVideos("playlist", null)).thenAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new UpstreamClient.PageItems(List.of("v1"), null);
        });
        when(upstream.video("v1")).thenReturn(download("v1"));
        when(catalog.existing("viewer", "v1")).thenReturn(Optional.empty());
        ArchivePreflightService service = service(upstream, catalog, new ArchiveSpeedService());
        try {
            ArchivePreflightService.ProgressSnapshot initial = service.start(
                    ArchiveModels.Kind.PLAYLIST, "playlist", "viewer", "estimate-id");

            assertThat(initial.status()).isEqualTo("COLLECTING");
            assertThat(initial.estimateId()).isEqualTo("estimate-id");
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            ArchivePreflightService.ProgressSnapshot completed = service.status("viewer", "estimate-id");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!completed.status().equals("COMPLETED") && System.nanoTime() < deadline) {
                Thread.sleep(10);
                completed = service.status("viewer", "estimate-id");
            }
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.processedVideoCount()).isEqualTo(1);
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void acceptsMoreThanHundredPagesWhenCursorAdvances() {
        UpstreamClient upstream = mock(UpstreamClient.class);
        CatalogClient catalog = mock(CatalogClient.class);
        when(upstream.playlistVideos(eq("playlist"), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            String cursor = invocation.getArgument(1);
            int page = cursor == null ? 0 : Integer.parseInt(cursor);
            return new UpstreamClient.PageItems(List.of("v" + page), page == 100 ? null : String.valueOf(page + 1));
        });
        when(upstream.video(anyString())).thenAnswer(invocation -> download(invocation.getArgument(0)));
        when(catalog.existing(eq("viewer"), anyString())).thenReturn(Optional.empty());
        ArchivePreflightService service = service(upstream, catalog, new ArchiveSpeedService());
        try {
            ArchivePreflightService.Estimate estimate = service.estimate(
                    ArchiveModels.Kind.PLAYLIST, "playlist", "viewer").block();

            assertThat(estimate).isNotNull();
            assertThat(estimate.totalVideoCount()).isEqualTo(101);
            assertThat(estimate.videoIds()).hasSize(101);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void skipsOnlyAgeRestrictedVideosAndPreservesTheirReason() {
        UpstreamClient upstream = mock(UpstreamClient.class);
        CatalogClient catalog = mock(CatalogClient.class);
        when(upstream.playlistVideos("playlist", null))
                .thenReturn(new UpstreamClient.PageItems(List.of("v1", "age", "v2"), null));
        org.springframework.web.client.HttpClientErrorException age = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "Bad Gateway", org.springframework.http.HttpHeaders.EMPTY,
                "{\"code\":\"ANTIBOT\",\"message\":\"Sign in to confirm your age\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
        when(upstream.video("age")).thenThrow(age);
        when(upstream.video("v1")).thenReturn(download("v1"));
        when(upstream.video("v2")).thenReturn(download("v2"));
        when(catalog.existing(eq("viewer"), anyString())).thenReturn(Optional.empty());
        ArchivePreflightService service = service(upstream, catalog, new ArchiveSpeedService());
        try {
            ArchivePreflightService.Estimate estimate = service.estimate(
                    ArchiveModels.Kind.PLAYLIST, "playlist", "viewer").block();

            assertThat(estimate).isNotNull();
            assertThat(estimate.totalVideoCount()).isEqualTo(3);
            assertThat(estimate.newVideoCount()).isEqualTo(2);
            assertThat(estimate.skippedVideoCount()).isEqualTo(1);
            assertThat(estimate.videoIds()).containsExactly("v1", "v2");
            assertThat(estimate.totalBytes()).isEqualTo(3750);
            assertThat(estimate.skippedVideos()).singleElement().satisfies(skipped -> {
                assertThat(skipped.videoId()).isEqualTo("age");
                assertThat(skipped.reason()).contains("Sign in to confirm your age");
            });
            assertThat(service.status("viewer", estimate.estimateId()).skippedVideoCount()).isEqualTo(1);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void skipsCopyrightBlockedVideoAndPreservesItsReason() {
        UpstreamClient upstream = mock(UpstreamClient.class);
        CatalogClient catalog = mock(CatalogClient.class);
        when(upstream.playlistVideos("playlist", null))
                .thenReturn(new UpstreamClient.PageItems(List.of("v1", "blocked", "v2"), null));
        org.springframework.web.client.HttpClientErrorException blocked = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "Bad Gateway", org.springframework.http.HttpHeaders.EMPTY,
                "{\"code\":\"FAILED\",\"message\":\"Video unavailable. It was blocked due to the claimed content by Nintendo.\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
        when(upstream.video("blocked")).thenThrow(blocked);
        when(upstream.video("v1")).thenReturn(download("v1"));
        when(upstream.video("v2")).thenReturn(download("v2"));
        when(catalog.existing(eq("viewer"), anyString())).thenReturn(Optional.empty());
        ArchivePreflightService service = service(upstream, catalog, new ArchiveSpeedService());
        try {
            ArchivePreflightService.Estimate estimate = service.estimate(
                    ArchiveModels.Kind.PLAYLIST, "playlist", "viewer").block();

            assertThat(estimate).isNotNull();
            assertThat(estimate.totalVideoCount()).isEqualTo(3);
            assertThat(estimate.newVideoCount()).isEqualTo(2);
            assertThat(estimate.skippedVideoCount()).isEqualTo(1);
            assertThat(estimate.videoIds()).containsExactly("v1", "v2");
            assertThat(estimate.skippedVideos()).singleElement().satisfies(skipped -> {
                assertThat(skipped.videoId()).isEqualTo("blocked");
                assertThat(skipped.reason()).contains("blocked due to the claimed content by Nintendo");
            });
        } finally {
            service.shutdown();
        }
    }

    @Test
    void retriesTimeoutOnceAndCompletes() {
        UpstreamClient upstream = mock(UpstreamClient.class);
        CatalogClient catalog = mock(CatalogClient.class);
        when(upstream.playlistVideos("playlist", null)).thenReturn(new UpstreamClient.PageItems(List.of("v1"), null));
        org.springframework.web.client.HttpClientErrorException timeout = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "Bad Gateway", org.springframework.http.HttpHeaders.EMPTY,
                "{\"code\":\"FAILED\",\"message\":\"yt-dlp timed out after PT1M\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
        when(upstream.video("v1")).thenThrow(timeout).thenReturn(download("v1"));
        when(catalog.existing("viewer", "v1")).thenReturn(Optional.empty());
        ArchivePreflightService service = service(upstream, catalog, new ArchiveSpeedService());
        try {
            ArchivePreflightService.Estimate estimate = service.estimate(
                    ArchiveModels.Kind.PLAYLIST, "playlist", "viewer").block();

            assertThat(estimate).isNotNull();
            assertThat(estimate.totalVideoCount()).isEqualTo(1);
            assertThat(estimate.skippedVideoCount()).isZero();
            verify(upstream, times(2)).video("v1");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void doesNotSkipTemporaryUpstreamFailures() {
        UpstreamClient upstream = mock(UpstreamClient.class);
        CatalogClient catalog = mock(CatalogClient.class);
        when(upstream.playlistVideos("playlist", null)).thenReturn(new UpstreamClient.PageItems(List.of("v1"), null));
        org.springframework.web.client.HttpClientErrorException failure = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "Bad Gateway", org.springframework.http.HttpHeaders.EMPTY,
                "{\"code\":\"FAILED\",\"message\":\"Connection timed out\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
        when(upstream.video("v1")).thenThrow(failure);
        ArchivePreflightService service = service(upstream, catalog, new ArchiveSpeedService());
        try {
            assertThatThrownBy(() -> service.estimate(ArchiveModels.Kind.PLAYLIST, "playlist", "viewer").block())
                    .isSameAs(failure);
            verify(upstream, times(3)).video("v1");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void resumesFailedEstimateWithoutRecomputingCompletedVideos() throws Exception {
        UpstreamClient upstream = mock(UpstreamClient.class);
        CatalogClient catalog = mock(CatalogClient.class);
        when(upstream.playlistVideos("playlist", null))
                .thenReturn(new UpstreamClient.PageItems(List.of("v1", "v2"), null));
        when(upstream.video("v1")).thenReturn(download("v1"));
        org.springframework.web.client.HttpClientErrorException timeout = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "Bad Gateway", org.springframework.http.HttpHeaders.EMPTY,
                "{\"code\":\"FAILED\",\"message\":\"yt-dlp timed out after PT1M\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
        when(upstream.video("v2")).thenThrow(timeout).thenThrow(timeout).thenThrow(timeout).thenReturn(download("v2"));
        when(catalog.existing(eq("viewer"), anyString())).thenReturn(Optional.empty());
        ArchivePreflightService service = service(upstream, catalog, new ArchiveSpeedService());
        try {
            assertThatThrownBy(() -> service.estimate(ArchiveModels.Kind.PLAYLIST, "playlist", "viewer", "resume-id").block())
                    .isSameAs(timeout);
            assertThat(service.status("viewer", "resume-id").status()).isEqualTo("FAILED");
            assertThat(service.status("viewer", "resume-id").processedVideoCount()).isEqualTo(1);

            ArchivePreflightService.ProgressSnapshot resumed = service.start(
                    ArchiveModels.Kind.PLAYLIST, "playlist", "viewer", "resume-id");
            assertThat(resumed.status()).isNotEqualTo("FAILED");
            ArchivePreflightService.ProgressSnapshot completed = service.status("viewer", "resume-id");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!completed.status().equals("COMPLETED") && System.nanoTime() < deadline) {
                Thread.sleep(10);
                completed = service.status("viewer", "resume-id");
            }

            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.totalBytes()).isEqualTo(3750);
            assertThat(completed.processedVideoCount()).isEqualTo(2);
            verify(upstream, times(1)).video("v1");
            verify(upstream, times(4)).video("v2");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void doesNotSkipOtherAntibotFailures() {
        UpstreamClient upstream = mock(UpstreamClient.class);
        CatalogClient catalog = mock(CatalogClient.class);
        when(upstream.playlistVideos("playlist", null)).thenReturn(new UpstreamClient.PageItems(List.of("v1"), null));
        org.springframework.web.client.HttpClientErrorException failure = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "Bad Gateway", org.springframework.http.HttpHeaders.EMPTY,
                "{\"code\":\"ANTIBOT\",\"message\":\"Sign in to confirm you are not a bot\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
        when(upstream.video("v1")).thenThrow(failure);
        ArchivePreflightService service = service(upstream, catalog, new ArchiveSpeedService());
        try {
            assertThatThrownBy(() -> service.estimate(ArchiveModels.Kind.PLAYLIST, "playlist", "viewer").block())
                    .isSameAs(failure);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void preservesDownstreamErrorFromParallelCollection() {
        UpstreamClient upstream = mock(UpstreamClient.class);
        CatalogClient catalog = mock(CatalogClient.class);
        org.springframework.web.client.HttpClientErrorException failure =
                org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Bad Request",
                        org.springframework.http.HttpHeaders.EMPTY,
                        "{\"code\":\"INVALID_ARGUMENT\",\"message\":\"Invalid cursor\"}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8);
        when(upstream.channelVideos("channel", null)).thenThrow(failure);
        when(upstream.channelLiveStreams("channel", null)).thenReturn(new UpstreamClient.PageItems(List.of(), null));
        when(upstream.channelPlaylists("channel", null)).thenReturn(new UpstreamClient.PageItems(List.of(), null));
        ArchivePreflightService service = service(upstream, catalog, new ArchiveSpeedService());
        try {
            assertThatThrownBy(() -> service.estimate(ArchiveModels.Kind.CHANNEL, "channel", "viewer").block())
                    .isSameAs(failure);
        } finally {
            service.shutdown();
        }
    }

    private static ArchivePreflightService service(UpstreamClient upstream, CatalogClient catalog,
            ArchiveSpeedService speed) {
        return new ArchivePreflightService(upstream, catalog, speed, new ArchivePreflightEvents(new ObjectMapper()));
    }

    private static UpstreamClient.VideoDownload download(String videoId) {
        ArchiveModels.ExistingVideo metadata = new ArchiveModels.ExistingVideo("", "channel", "Channel", null,
                null, null, null, videoId, "Video", null, 100L, "/archive/channel/video.mkv", "bv*+ba", 0L,
                1920, 1080, "avc1", "opus", null, 500L);
        UpstreamClient.FormatOption video = new UpstreamClient.FormatOption("137", "video/mp4", "avc1",
                1920, 1080, 25.0, 1000L, true, false, 1250L);
        UpstreamClient.FormatOption audio = new UpstreamClient.FormatOption("251", "audio/webm", "opus",
                null, null, null, 500L, false, true, 625L);
        return new UpstreamClient.VideoDownload(metadata, 1875L, List.of(video, audio), List.of(), List.of());
    }
}
