package dev.spanktube.upstream;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.utils.JsonUtils;
import org.springframework.stereotype.Component;

@Component
class LiveDashClient {

    LiveManifestUrls manifestUrls(String videoId) {
        try {
            InnertubeClientRequestInfo requestInfo = InnertubeClientRequestInfo.ofWebClient();
            var headers = YoutubeParsingHelper.getYouTubeHeaders();
            requestInfo.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion();
            requestInfo.clientInfo.visitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(requestInfo,
                    Localization.DEFAULT, ContentCountry.DEFAULT, headers,
                    "https://www.youtube.com/youtubei/v1/", null, false);
            var body = YoutubeParsingHelper.prepareJsonBuilder(Localization.DEFAULT, ContentCountry.DEFAULT,
                    requestInfo, null)
                    .value("videoId", videoId)
                    .value("contentCheckOk", true)
                    .value("racyCheckOk", true)
                    .done();
            var response = NewPipe.getDownloader().postWithContentTypeJson(
                    "https://www.youtube.com/youtubei/v1/player?prettyPrint=false", headers,
                    JsonWriter.string(body).getBytes(StandardCharsets.UTF_8), Localization.DEFAULT);
            var player = JsonUtils.toJsonObject(YoutubeParsingHelper.getValidJsonResponseBody(response));
            LiveManifestUrls result = new LiveManifestUrls(fromPlayerResponse(player).orElse(null),
                    hlsFromPlayerResponse(player).orElse(null));
            if (result.dashUrl() == null && result.hlsUrl() == null) {
                JsonObject streaming = player.getObject("streamingData");
                org.slf4j.LoggerFactory.getLogger(LiveDashClient.class).info(
                        "No supported live manifest in full web response for {}: streamingData keys {}",
                        videoId, streaming == null ? java.util.List.of() : streaming.keySet());
            }
            if (result.dashUrl() == null && result.hlsUrl() == null) {
                throw UpstreamException.failed("Full Web returned no live manifest");
            }
            return result;
        } catch (UpstreamException error) {
            try {
                JsonObject response = org.schabi.newpipe.extractor.services.youtube.YoutubeStreamHelper
                        .getVisionOsPlayerResponse(ContentCountry.DEFAULT, Localization.DEFAULT, videoId,
                                YoutubeParsingHelper.generateContentPlaybackNonce());
                return new LiveManifestUrls(fromPlayerResponse(response).orElse(null),
                        hlsFromPlayerResponse(response).orElse(null));
            } catch (Exception visionError) {
                if (visionError instanceof UpstreamException upstream
                        && upstream.reason() == UpstreamException.Reason.ANTIBOT) {
                    throw upstream;
                }
                throw UpstreamException.failed(error.getMessage() + "; VisionOS: " + visionError.getMessage(), error);
            }
        } catch (Exception error) {
            throw UpstreamException.failed("Web live manifest client: " + error.getMessage(), error);
        }
    }

    Optional<VideoDetails> liveVideo(String videoId) {
        try {
            JsonObject response = org.schabi.newpipe.extractor.services.youtube.YoutubeStreamHelper
                    .getVisionOsPlayerResponse(ContentCountry.DEFAULT, Localization.DEFAULT, videoId,
                            YoutubeParsingHelper.generateContentPlaybackNonce());
            return fromVisionOs(videoId, response);
        } catch (UpstreamException error) {
            throw error;
        } catch (Exception error) {
            throw UpstreamException.failed("VisionOS live client: " + error.getMessage(), error);
        }
    }

    static Optional<VideoDetails> fromVisionOs(String videoId, JsonObject response) {
        JsonObject details = response.getObject("videoDetails");
        JsonObject playability = response.getObject("playabilityStatus");
        if (details == null || !details.getBoolean("isLive", false)
                && (playability == null || !playability.has("liveStreamability"))) {
            return Optional.empty();
        }
        String dash = fromPlayerResponse(response).orElse(null);
        String hls = hlsFromPlayerResponse(response).orElse(null);
        if (dash == null && hls == null) {
            return Optional.empty();
        }
        String thumbnail = null;
        JsonObject images = details.getObject("thumbnail");
        if (images != null) {
            for (Object image : images.getArray("thumbnails")) {
                if (image instanceof JsonObject entry) {
                    thumbnail = entry.getString("url");
                }
            }
        }
        JsonObject microformat = response.getObject("microformat");
        JsonObject renderer = microformat == null ? null : microformat.getObject("playerMicroformatRenderer");
        String publishedAt = renderer == null ? null : renderer.getString("publishDate", renderer.getString("uploadDate"));
        long views;
        try {
            views = Long.parseLong(details.getString("viewCount", "-1"));
        } catch (NumberFormatException error) {
            views = -1;
        }
        return Optional.of(new VideoDetails(videoId, details.getString("title", videoId),
                details.getString("shortDescription"), details.getString("channelId"), details.getString("author"),
                null, null, null, null, null, views < 0 ? null : views, null, publishedAt, thumbnail,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), false, "LIVE_STREAM", dash, hls));
    }

    record LiveManifestUrls(String dashUrl, String hlsUrl) {
    }

    static Optional<String> hlsFromPlayerResponse(JsonObject response) {
        JsonObject streaming = response.getObject("streamingData");
        String url = streaming == null ? null : streaming.getString("hlsManifestUrl");
        return url != null && url.startsWith("https://") ? Optional.of(url) : Optional.empty();
    }

    static Optional<String> fromPlayerResponse(JsonObject response) {
        JsonObject playability = response.getObject("playabilityStatus");
        String status = playability == null ? null : playability.getString("status");
        if (status != null && !"OK".equals(status)) {
            String reason = playability.getString("reason", status);
            if ("LOGIN_REQUIRED".equals(status)) {
                throw UpstreamException.antiBot(reason);
            }
            throw UpstreamException.failed("Web live DASH client: " + reason);
        }
        JsonObject streaming = response.getObject("streamingData");
        String url = streaming == null ? null : streaming.getString("dashManifestUrl");
        if (url == null || !url.startsWith("https://") || url.contains("m3u8")) {
            return Optional.empty();
        }
        if (!url.contains("mpd_version=")) {
            url += (url.contains("?") ? "&" : "?") + "mpd_version=7";
        }
        return Optional.of(url);
    }
}
