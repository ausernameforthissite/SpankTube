package dev.spanktube.media;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

@Component
class DashManifestGenerator {

    String generate(UpstreamVideo video) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document document = factory.newDocumentBuilder().newDocument();
            Element mpd = element(document, document, "MPD");
            mpd.setAttribute("xmlns", "urn:mpeg:dash:schema:mpd:2011");
            mpd.setAttribute("type", "static");
            mpd.setAttribute("profiles", "urn:mpeg:dash:profile:isoff-on-demand:2011");
            mpd.setAttribute("minBufferTime", "PT1.5S");
            if (video.durationSeconds() != null) {
                mpd.setAttribute("mediaPresentationDuration", "PT" + video.durationSeconds() + "S");
            }
            Element period = element(document, mpd, "Period");

            Map<String, List<UpstreamVideo.Format>> videoSets = new LinkedHashMap<>();
            Map<String, List<UpstreamVideo.Format>> audioSets = new LinkedHashMap<>();
            for (UpstreamVideo.Format format : video.formats()) {
                if (format.hasVideo()) {
                    videoSets.computeIfAbsent(format.mimeType(), key -> new java.util.ArrayList<>()).add(format);
                } else if (format.hasAudio()) {
                    audioSets.computeIfAbsent(format.mimeType(), key -> new java.util.ArrayList<>()).add(format);
                }
            }
            int adaptationId = 0;
            for (Map.Entry<String, List<UpstreamVideo.Format>> entry : videoSets.entrySet()) {
                addAdaptationSet(document, period, video, "video", entry.getKey(),
                        entry.getValue(), adaptationId++);
            }
            for (Map.Entry<String, List<UpstreamVideo.Format>> entry : audioSets.entrySet()) {
                addAdaptationSet(document, period, video, "audio", entry.getKey(),
                        entry.getValue(), adaptationId++);
            }
            for (UpstreamVideo.Subtitle subtitle : video.subtitles()) {
                Element adaptation = adaptation(document, period, adaptationId++, "text", "text/vtt");
                adaptation.setAttribute("lang", subtitle.language());
                Element representation = element(document, adaptation, "Representation");
                representation.setAttribute("id", subtitle.trackId());
                representation.setAttribute("bandwidth", "1");
                textElement(document, representation, "BaseURL",
                        "/media/v/" + video.videoId() + "/sub/" + subtitle.trackId());
            }

            TransformerFactory transformers = TransformerFactory.newInstance();
            transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = transformers.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            StringWriter output = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toString();
        } catch (Exception error) {
            throw new IllegalStateException("Unable to generate DASH manifest", error);
        }
    }

    private static void addAdaptationSet(Document document, Element period, UpstreamVideo video,
            String contentType, String mimeType, List<UpstreamVideo.Format> formats, int adaptationId) {
        Element adaptation = adaptation(document, period, adaptationId, contentType, mimeType);
        for (UpstreamVideo.Format format : formats) {
            Element representation = element(document, adaptation, "Representation");
            representation.setAttribute("id", format.formatId());
            representation.setAttribute("bandwidth", Long.toString(Math.max(1,
                    format.bitrate() == null ? 1 : format.bitrate())));
            if (format.codecs() != null) {
                representation.setAttribute("codecs", format.codecs());
            }
            if (format.width() != null) {
                representation.setAttribute("width", format.width().toString());
            }
            if (format.height() != null) {
                representation.setAttribute("height", format.height().toString());
            }
            if (format.fps() != null) {
                representation.setAttribute("frameRate", format.fps().toString());
            }
            if (format.audioSampleRate() != null) {
                representation.setAttribute("audioSamplingRate", format.audioSampleRate().toString());
            }
            String segmentUrl = "/media/v/" + video.videoId() + "/seg/" + format.formatId();
            if (format.indexRange() != null) {
                textElement(document, representation, "BaseURL", segmentUrl);
                Element segmentBase = element(document, representation, "SegmentBase");
                segmentBase.setAttribute("indexRange", format.indexRange());
                segmentBase.setAttribute("indexRangeExact", "true");
                if (format.initRange() != null) {
                    Element initialization = element(document, segmentBase, "Initialization");
                    initialization.setAttribute("range", format.initRange());
                }
            } else {
                addSegmentList(document, representation, video, format, segmentUrl);
            }
        }
    }

    private static void addSegmentList(Document document, Element representation, UpstreamVideo video,
            UpstreamVideo.Format format, String segmentUrl) {
        Element segmentList = element(document, representation, "SegmentList");
        segmentList.setAttribute("timescale", "1");
        long duration = video.durationSeconds() == null ? 1 : Math.max(1, video.durationSeconds());
        segmentList.setAttribute("duration", Long.toString(duration));
        Element segmentUrlElement = element(document, segmentList, "SegmentURL");
        segmentUrlElement.setAttribute("media", segmentUrl);
    }

    private static Element adaptation(Document document, Element period, int id,
            String contentType, String mimeType) {
        Element adaptation = element(document, period, "AdaptationSet");
        adaptation.setAttribute("id", Integer.toString(id));
        adaptation.setAttribute("contentType", contentType);
        adaptation.setAttribute("mimeType", mimeType);
        adaptation.setAttribute("segmentAlignment", "true");
        return adaptation;
    }

    private static Element element(Document document, org.w3c.dom.Node parent, String name) {
        Element element = document.createElement(name);
        parent.appendChild(element);
        return element;
    }

    private static void textElement(Document document, Element parent, String name, String value) {
        element(document, parent, name).setTextContent(value);
    }
}
