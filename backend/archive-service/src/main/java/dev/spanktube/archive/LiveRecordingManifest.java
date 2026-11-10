package dev.spanktube.archive;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class LiveRecordingManifest {

    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Z-]+)=(\"[^\"]*\"|[^,]*)");
    private static final Pattern RESOLUTION = Pattern.compile("(\\d+)x(\\d+)");

    private LiveRecordingManifest() {
    }

    record Selection(List<String> inputs, List<String> maps) {
    }

    static Selection select(Path manifest, String mediaServiceUrl) throws Exception {
        return select(manifest, mediaServiceUrl, null, null);
    }

    static Selection select(Path manifest, String mediaServiceUrl, Integer height, Long bandwidth) throws Exception {
        String text = Files.readString(manifest);
        if (manifest.getFileName().toString().endsWith(".m3u8")) {
            return hls(text, mediaServiceUrl, height, bandwidth);
        }
        return dash(text, manifest.getParent(), mediaServiceUrl, height, bandwidth);
    }

    private static Selection hls(String manifest, String mediaServiceUrl, Integer height, Long bandwidth) {
        String[] lines = manifest.split("\\R");
        Variant best = null;
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].startsWith("#EXT-X-STREAM-INF:")) {
                continue;
            }
            String uri = nextUri(lines, index + 1);
            if (uri == null) {
                continue;
            }
            Variant candidate = new Variant(uri, attribute(lines[index], "BANDWIDTH"),
                    attribute(lines[index], "RESOLUTION"), attribute(lines[index], "AUDIO"));
            if ((height == null || candidate.height() == height)
                    && (bandwidth == null || candidate.bandwidthValue() == bandwidth)
                    && (best == null || candidate.compareTo(best) > 0)) {
                best = candidate;
            }
        }
        if (best == null) {
            if (height != null || bandwidth != null) {
                throw new ArchiveDownloadException("Selected video quality is no longer available");
            }
            throw new ArchiveDownloadException("HLS master has no video variants");
        }
        List<String> inputs = new ArrayList<>();
        List<String> maps = new ArrayList<>();
        inputs.add(absolute(best.uri, mediaServiceUrl));
        String audio = audio(lines, best.group);
        if (audio != null) {
            inputs.add(absolute(audio, mediaServiceUrl));
            maps.add("0:v:0");
            maps.add("1:a:0");
        } else {
            maps.add("0");
        }
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].startsWith("#EXT-X-MEDIA:") && "SUBTITLES".equals(attribute(lines[index], "TYPE"))) {
                String uri = attribute(lines[index], "URI");
                if (uri != null) {
                    int input = inputs.size();
                    inputs.add(absolute(uri, mediaServiceUrl));
                    maps.add(input + ":0");
                }
            }
        }
        return new Selection(List.copyOf(inputs), List.copyOf(maps));
    }

    private static Selection dash(String text, Path directory, String mediaServiceUrl, Integer height, Long bandwidth)
            throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Element bestVideo = null;
        Element bestAudio = null;
        long bestBandwidth = -1;
        var representations = document.getElementsByTagNameNS("*", "Representation");
        for (int index = 0; index < representations.getLength(); index++) {
            Element representation = (Element) representations.item(index);
            Element adaptation = (Element) representation.getParentNode();
            String type = adaptation.getAttribute("contentType").isBlank()
                    ? adaptation.getAttribute("mimeType") : adaptation.getAttribute("contentType");
            boolean qualityMatches = (height == null || number(representation, "height", 0) == height)
                    && (bandwidth == null || number(representation, "bandwidth", 0) == bandwidth);
            if (type.startsWith("video") && qualityMatches && bestVideo == null) {
                bestVideo = representation;
            }
            if (type.startsWith("audio") && bestAudio == null) {
                bestAudio = representation;
            }
            if (type.startsWith("video") && qualityMatches && bandwidth(representation) > bestBandwidth) {
                bestVideo = representation;
                bestBandwidth = bandwidth(representation);
            }
        }
        if ((height != null || bandwidth != null) && bestVideo == null) {
            throw new ArchiveDownloadException("Selected video quality is no longer available");
        }
        if (bestVideo == null && bestAudio == null) {
            throw new ArchiveDownloadException("DASH manifest has no media representations");
        }
        for (int index = representations.getLength() - 1; index >= 0; index--) {
            Element representation = (Element) representations.item(index);
            if (representation != bestVideo && representation != bestAudio) {
                representation.getParentNode().removeChild(representation);
            }
        }
        rewriteUrls(document, mediaServiceUrl);
        TransformerFactory transformers = TransformerFactory.newInstance();
        transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        var transformer = transformers.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        StringWriter output = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        Path target = directory.resolve("recording.mpd");
        Files.writeString(target, output.toString());
        List<String> maps = new ArrayList<>();
        if (bestVideo != null) maps.add("0:v:0");
        if (bestAudio != null) maps.add("0:a:0");
        return new Selection(List.of(target.toString()), List.copyOf(maps));
    }

    private static String audio(String[] lines, String group) {
        String fallback = null;
        for (String line : lines) {
            if (!line.startsWith("#EXT-X-MEDIA:") || !"AUDIO".equals(attribute(line, "TYPE"))) {
                continue;
            }
            String uri = attribute(line, "URI");
            if (uri == null) {
                continue;
            }
            if (fallback == null) {
                fallback = uri;
            }
            if (group != null && group.equals(attribute(line, "GROUP-ID"))) {
                return uri;
            }
            if ("YES".equals(attribute(line, "DEFAULT"))) {
                return uri;
            }
        }
        return fallback;
    }

    private static String nextUri(String[] lines, int start) {
        for (int index = start; index < lines.length; index++) {
            String value = lines[index].strip();
            if (!value.isEmpty() && !value.startsWith("#")) {
                return value;
            }
        }
        return null;
    }

    private static String attribute(String line, String name) {
        Matcher matcher = ATTRIBUTE.matcher(line);
        while (matcher.find()) {
            if (name.equals(matcher.group(1))) {
                String value = matcher.group(2);
                return value.startsWith("\"") ? value.substring(1, value.length() - 1) : value;
            }
        }
        return null;
    }

    private static String absolute(String uri, String mediaServiceUrl) {
        if (uri.startsWith("/")) {
            return mediaServiceUrl + uri;
        }
        if (uri.startsWith(mediaServiceUrl + "/")) {
            return uri;
        }
        throw new ArchiveDownloadException("Media playlist URL does not belong to media-service");
    }

    private static long bandwidth(Element representation) {
        long height = number(representation, "height", 0);
        long bandwidth = number(representation, "bandwidth", 0);
        return height * 10_000_000L + bandwidth;
    }

    private static long number(Element element, String name, long fallback) {
        try {
            return element.getAttribute(name).isBlank() ? fallback : Long.parseLong(element.getAttribute(name));
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static void rewriteUrls(Document document, String mediaServiceUrl) {
        String[] tags = { "SegmentTemplate", "SegmentURL", "Initialization" };
        for (String tag : tags) {
            var elements = document.getElementsByTagNameNS("*", tag);
            for (int index = 0; index < elements.getLength(); index++) {
                Element element = (Element) elements.item(index);
                for (String attribute : new String[] { "media", "initialization", "index", "sourceURL" }) {
                    String value = element.getAttribute(attribute);
                    if (value.startsWith("/")) {
                        element.setAttribute(attribute, mediaServiceUrl + value);
                    }
                }
            }
        }
        var baseUrls = document.getElementsByTagNameNS("*", "BaseURL");
        for (int index = 0; index < baseUrls.getLength(); index++) {
            Node element = baseUrls.item(index);
            String value = element.getTextContent().trim();
            if (value.startsWith("/")) {
                element.setTextContent(mediaServiceUrl + value);
            }
        }
    }

    private record Variant(String uri, String bandwidth, String resolution, String group) implements Comparable<Variant> {
        int height() {
            Matcher matcher = RESOLUTION.matcher(resolution == null ? "" : resolution);
            return matcher.find() ? Integer.parseInt(matcher.group(2)) : 0;
        }

        long bandwidthValue() {
            try {
                return bandwidth == null ? 0 : Long.parseLong(bandwidth);
            } catch (NumberFormatException error) {
                return 0;
            }
        }

        @Override
        public int compareTo(Variant other) {
            int height = Integer.compare(height(), other.height());
            return height != 0 ? height : Long.compare(bandwidthValue(), other.bandwidthValue());
        }
    }
}
