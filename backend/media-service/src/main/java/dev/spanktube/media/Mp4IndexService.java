package dev.spanktube.media;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
class Mp4IndexService {

    private static final int HEADER_BYTES = 64 * 1024;
    private final WebClient youtubeClient;

    Mp4IndexService(@Qualifier("youtubeWebClient") WebClient youtubeClient) {
        this.youtubeClient = youtubeClient;
    }

    Mono<UpstreamVideo> enrich(UpstreamVideo video) {
        return Flux.fromIterable(video.formats())
                .flatMapSequential(this::enrichFormat, 4)
                .collectList()
                .map(formats -> new UpstreamVideo(video.videoId(), video.durationSeconds(), formats, video.subtitles()));
    }

    private Mono<UpstreamVideo.Format> enrichFormat(UpstreamVideo.Format format) {
        if (format.indexRange() != null || format.mimeType() == null || !format.mimeType().endsWith("/mp4")) {
            return Mono.just(format);
        }
        return youtubeClient.get()
                .uri(MediaUrlValidator.mediaUrl(format.url()))
                .header(HttpHeaders.RANGE, "bytes=0-" + (HEADER_BYTES - 1))
                .retrieve()
                .bodyToMono(byte[].class)
                .map(bytes -> withIndex(format, bytes));
    }

    static UpstreamVideo.Format withIndex(UpstreamVideo.Format format, byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int offset = 0;
        while (offset + 8 <= buffer.capacity()) {
            long size = Integer.toUnsignedLong(buffer.getInt(offset));
            String type = new String(bytes, offset + 4, 4, StandardCharsets.ISO_8859_1);
            int headerSize = 8;
            if (size == 1) {
                if (offset + 16 > buffer.capacity()) {
                    break;
                }
                size = buffer.getLong(offset + 8);
                headerSize = 16;
            }
            if (size < headerSize) {
                break;
            }
            if ("sidx".equals(type)) {
                String initRange = offset == 0 ? null : "0-" + (offset - 1);
                String indexRange = offset + "-" + (offset + size - 1);
                return new UpstreamVideo.Format(format.formatId(), format.url(), format.mimeType(), format.codecs(),
                        format.width(), format.height(), format.fps(), format.bitrate(), format.audioSampleRate(),
                        format.contentLength(), format.hasVideo(), format.hasAudio(), initRange, indexRange);
            }
            if (size == 0 || offset + size > buffer.capacity()) {
                break;
            }
            offset += (int) size;
        }
        return format;
    }
}
