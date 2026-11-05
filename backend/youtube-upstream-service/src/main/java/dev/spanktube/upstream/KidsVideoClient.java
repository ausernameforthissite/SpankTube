package dev.spanktube.upstream;

import java.util.Locale;
import java.util.Optional;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;
import dev.spanktube.egress.EgressProbe;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.services.youtube.YoutubeStreamHelper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class KidsVideoClient {

    private final EgressProbe egressProbe;
    private final KidsVideoParser parser;

    KidsVideoClient(ObjectProvider<EgressProbe> egressProbe, KidsVideoParser parser) {
        this.egressProbe = egressProbe.getIfAvailable();
        this.parser = parser;
    }

    Optional<VideoDetails> video(String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            throw new IllegalArgumentException("Invalid videoId");
        }
        if (egressProbe != null && !egressProbe.isAvailable()) {
            throw UpstreamException.unavailable("YouTube egress is unavailable: " + egressProbe.lastError());
        }
        try {
            JsonObject response = YoutubeStreamHelper.getAndroidReelPlayerResponse(ContentCountry.DEFAULT,
                    Localization.DEFAULT, videoId, YoutubeParsingHelper.generateContentPlaybackNonce());
            return parser.parse(videoId, JsonWriter.string(response));
        } catch (UpstreamException error) {
            throw error;
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("sign in to confirm") || normalized.contains("not a bot")
                    || normalized.contains("po token") || normalized.contains("po_token")
                    || normalized.contains("botguard")) {
                throw UpstreamException.antiBot(message);
            }
            throw UpstreamException.failed("Android Reel client: " + message, error);
        }
    }
}
