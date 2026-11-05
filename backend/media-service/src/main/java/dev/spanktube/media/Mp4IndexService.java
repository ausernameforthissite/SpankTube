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

    private static final int HEADER_BYTES = 256 * 1024;
    private final WebClient youtubeClient;
    private final PlaybackLogService playbackLogs;

    Mp4IndexService(@Qualifier("youtubeWebClient") WebClient youtubeClient, PlaybackLogService playbackLogs) {
        this.youtubeClient = youtubeClient;
        this.playbackLogs = playbackLogs;
    }

    Mono<UpstreamVideo> enrich(UpstreamVideo video, String playbackSession) {
        playbackLogs.log(video.videoId(), playbackSession, "Чтение MP4-индексов форматов…");
        return Flux.fromIterable(video.formats())
                .flatMapSequential(format -> enrichFormat(video.videoId(), format, playbackSession), 4)
                .collectList()
                .map(formats -> new UpstreamVideo(video.videoId(), video.durationSeconds(), formats, video.subtitles()));
    }

    private Mono<UpstreamVideo.Format> enrichFormat(String videoId, UpstreamVideo.Format format,
            String playbackSession) {
        if (format.indexRange() != null || format.mimeType() == null || !format.mimeType().endsWith("/mp4")) {
            return Mono.just(format);
        }
        playbackLogs.log(videoId, playbackSession, "Чтение MP4-индекса: формат " + format.formatId());
        return youtubeClient.get()
                .uri(MediaUrlValidator.mediaUrl(format.url()))
                .header(HttpHeaders.RANGE, "bytes=0-" + (HEADER_BYTES - 1))
                .retrieve()
                .bodyToMono(byte[].class)
                .map(bytes -> withIndex(format, bytes))
                .retryWhen(MediaRetry.transientFailures())
                .doOnNext(enriched -> playbackLogs.log(videoId, playbackSession,
                        "MP4-индекс готов: формат " + format.formatId()));
    }

    static UpstreamVideo.Format withIndex(UpstreamVideo.Format format, byte[] bytes) {
        format = withAvcCodec(format, bytes);
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

    private static UpstreamVideo.Format withAvcCodec(UpstreamVideo.Format format, byte[] bytes) {
        if (!format.hasVideo() || format.codecs() == null) {
            return format;
        }
        for (int offset = 0; offset + 8 <= bytes.length; offset++) {
            if (bytes[offset] != 'a' || bytes[offset + 1] != 'v' || bytes[offset + 2] != 'c'
                    || bytes[offset + 3] != 'C') {
                continue;
            }
            String codecs = String.format("avc1.%02X%02X%02X", bytes[offset + 5], bytes[offset + 6],
                    bytes[offset + 7]);
            int audioSeparator = format.codecs().indexOf(',');
            if (audioSeparator >= 0) {
                codecs += format.codecs().substring(audioSeparator);
            } else if (format.hasAudio()) {
                codecs += ",mp4a.40.2";
            }
            return new UpstreamVideo.Format(format.formatId(), format.url(), format.mimeType(), codecs,
                    format.width(), format.height(), format.fps(), format.bitrate(), format.audioSampleRate(),
                    format.contentLength(), format.hasVideo(), format.hasAudio(), format.initRange(),
                    format.indexRange());
        }
        return format;
    }
}
