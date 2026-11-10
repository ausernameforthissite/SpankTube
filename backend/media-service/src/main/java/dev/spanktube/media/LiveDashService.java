package dev.spanktube.media;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import reactor.core.publisher.Mono;

@Service
class LiveDashService {

    private static final Pattern TEMPLATE = Pattern.compile(
            "\\$(?:Number|RepresentationID|Bandwidth|Time)(?:%0[1-9][0-9]*d)?\\$");
    private final WebClient youtubeClient;

    LiveDashService(@Qualifier("youtubeWebClient") WebClient youtubeClient) {
        this.youtubeClient = youtubeClient.mutate()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();
    }

    Mono<String> manifest(UpstreamVideo video) {
        if (!video.isLive() || video.liveDashUrl() == null || video.liveDashUrl().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Live stream has no DASH manifest; use live.m3u8 when HLS is available"));
        }
        URI source = MediaUrlValidator.manifestUrl(video.liveDashUrl());
        return youtubeClient.get()
                .uri(source)
                .retrieve()
                .bodyToMono(String.class)
                .map(xml -> rewrite(video.videoId(), source, xml))
                .retryWhen(MediaRetry.transientFailures());
    }

    static String rewrite(String videoId, URI source, String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            java.util.Map<Node, String> bases = new java.util.IdentityHashMap<>();
            var baseUrls = document.getElementsByTagNameNS("*", "BaseURL");
            for (int index = 0; index < baseUrls.getLength(); index++) {
                Element element = (Element) baseUrls.item(index);
                bases.put(element, element.getTextContent().trim());
            }
            rewriteAttributes(document, "SegmentTemplate", new String[] { "media", "initialization", "index" },
                    videoId, source, bases);
            rewriteAttributes(document, "SegmentURL", new String[] { "media", "index" }, videoId, source, bases);
            rewriteAttributes(document, "Initialization", new String[] { "sourceURL" }, videoId, source, bases);
            rewriteElements(document, "BaseURL", videoId, source, bases);
            TransformerFactory transformers = TransformerFactory.newInstance();
            transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = transformers.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            StringWriter output = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toString();
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to rewrite live DASH manifest", error);
        }
    }

    private static void rewriteElements(Document document, String tag, String videoId, URI source,
            java.util.Map<Node, String> bases) {
        var elements = document.getElementsByTagNameNS("*", tag);
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            String target = bases.get(element);
            if (target != null && !target.isEmpty()) {
                element.setTextContent(proxy(videoId, resolve(element, source, target, bases)));
            }
        }
    }

    private static void rewriteAttributes(Document document, String tag, String[] attributes,
            String videoId, URI source, java.util.Map<Node, String> bases) {
        var elements = document.getElementsByTagNameNS("*", tag);
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            for (String attribute : attributes) {
                if (element.hasAttribute(attribute)) {
                    element.setAttribute(attribute, proxy(videoId,
                            resolve(element, source, element.getAttribute(attribute), bases)));
                }
            }
        }
    }

    private static URI resolve(Element element, URI source, String target, java.util.Map<Node, String> bases) {
        java.util.List<String> hierarchy = new java.util.ArrayList<>();
        for (Node node = element.getParentNode(); node instanceof Element parent; node = node.getParentNode()) {
            for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element candidate && bases.containsKey(candidate)) {
                    hierarchy.addFirst(bases.get(candidate));
                    break;
                }
            }
        }
        URI base = source;
        for (String inherited : hierarchy) {
            base = base.resolve(inherited);
        }
        URI resolved = base.resolve(target);
        return MediaUrlValidator.manifestUrl(resolved.toString());
    }

    private static String proxy(String videoId, URI target) {
        StringBuilder encoded = new StringBuilder();
        Matcher matcher = TEMPLATE.matcher(target.toASCIIString());
        int previous = 0;
        while (matcher.find()) {
            encoded.append(encode(target.toASCIIString().substring(previous, matcher.start())));
            encoded.append(matcher.group());
            previous = matcher.end();
        }
        encoded.append(encode(target.toASCIIString().substring(previous)));
        return "/media/v/" + videoId + "/live/segment?url=" + encoded;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
