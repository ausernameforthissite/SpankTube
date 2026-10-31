package dev.spanktube.egress;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("spanktube.egress")
public record EgressProperties(
        Mode mode,
        String host,
        int port,
        String username,
        String password,
        boolean remoteDns,
        Probe probe) {

    public EgressProperties {
        if (mode == null) {
            mode = Mode.SOCKS5;
        }
        if (port == 0) {
            port = 1080;
        }
        if (host == null) {
            host = "";
        }
        if (probe == null) {
            probe = new Probe(null, null, null, null);
        }
    }

    public enum Mode {
        SOCKS5,
        DIRECT
    }

    public record Probe(Boolean enabled, Duration interval, Duration timeout, URI uri) {
        public Probe {
            if (enabled == null) {
                enabled = true;
            }
            if (interval == null) {
                interval = Duration.ofSeconds(30);
            }
            if (timeout == null) {
                timeout = Duration.ofSeconds(10);
            }
            if (uri == null) {
                uri = URI.create("https://www.youtube.com/generate_204");
            }
        }
    }
}
