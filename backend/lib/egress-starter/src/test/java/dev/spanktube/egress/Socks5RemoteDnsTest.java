package dev.spanktube.egress;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class Socks5RemoteDnsTest {

    @Test
    void sendsUnresolvedHostnameToAuthenticatedSocks5Proxy() throws Exception {
        try (Socks5Server proxy = new Socks5Server()) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(EgressAutoConfiguration.class))
                    .withPropertyValues(
                            "spanktube.egress.mode=socks5",
                            "spanktube.egress.host=127.0.0.1",
                            "spanktube.egress.port=" + proxy.port(),
                            "spanktube.egress.username=viewer",
                            "spanktube.egress.password=secret",
                            "spanktube.egress.remote-dns=true",
                            "spanktube.egress.probe.enabled=false")
                    .run(context -> {
                        WebClient client = context.getBean("youtubeWebClient", WebClient.class);
                        String response = client.get()
                                .uri("http://remote-dns.invalid/media")
                                .retrieve()
                                .bodyToMono(String.class)
                                .block(Duration.ofSeconds(10));

                        assertThat(response).isEqualTo("ok");
                        proxy.awaitCompletion();
                        assertThat(proxy.addressType().get()).isEqualTo(3);
                        assertThat(proxy.hostname().get()).isEqualTo("remote-dns.invalid");
                        assertThat(proxy.username().get()).isEqualTo("viewer");
                        assertThat(proxy.password().get()).isEqualTo("secret");
                    });
        }
    }

    private static final class Socks5Server implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<?> task;
        private final AtomicReference<Integer> addressType = new AtomicReference<>();
        private final AtomicReference<String> hostname = new AtomicReference<>();
        private final AtomicReference<String> username = new AtomicReference<>();
        private final AtomicReference<String> password = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private Socks5Server() throws IOException {
            serverSocket = new ServerSocket(0);
            task = executor.submit(this::serve);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        AtomicReference<Integer> addressType() {
            return addressType;
        }

        AtomicReference<String> hostname() {
            return hostname;
        }

        AtomicReference<String> username() {
            return username;
        }

        AtomicReference<String> password() {
            return password;
        }

        void awaitCompletion() throws Exception {
            task.get(10, TimeUnit.SECONDS);
            if (failure.get() != null) {
                throw new AssertionError("SOCKS5 test server failed", failure.get());
            }
        }

        private void serve() {
            try (Socket socket = serverSocket.accept()) {
                socket.setSoTimeout(10_000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                negotiateAuthentication(input, output);
                readConnectRequest(input, output);
                readHttpRequest(input);
                output.write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
                        .getBytes(StandardCharsets.US_ASCII));
                output.flush();
            } catch (Throwable error) {
                failure.set(error);
            }
        }

        private void negotiateAuthentication(DataInputStream input, DataOutputStream output) throws IOException {
            assertThat(input.readUnsignedByte()).isEqualTo(5);
            int methodCount = input.readUnsignedByte();
            byte[] methods = input.readNBytes(methodCount);
            assertThat(methods).contains((byte) 2);
            output.write(new byte[]{5, 2});
            output.flush();

            assertThat(input.readUnsignedByte()).isEqualTo(1);
            username.set(readString(input));
            password.set(readString(input));
            output.write(new byte[]{1, 0});
            output.flush();
        }

        private void readConnectRequest(DataInputStream input, DataOutputStream output) throws IOException {
            assertThat(input.readUnsignedByte()).isEqualTo(5);
            assertThat(input.readUnsignedByte()).isEqualTo(1);
            input.readUnsignedByte();
            int type = input.readUnsignedByte();
            addressType.set(type);
            if (type == 3) {
                hostname.set(readString(input));
            } else if (type == 1) {
                byte[] address = input.readNBytes(4);
                hostname.set("%d.%d.%d.%d".formatted(
                        Byte.toUnsignedInt(address[0]), Byte.toUnsignedInt(address[1]),
                        Byte.toUnsignedInt(address[2]), Byte.toUnsignedInt(address[3])));
            } else {
                throw new IOException("Unexpected SOCKS5 address type " + type);
            }
            input.readUnsignedShort();
            output.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
            output.flush();
        }

        private static String readString(DataInputStream input) throws IOException {
            int length = input.readUnsignedByte();
            return new String(input.readNBytes(length), StandardCharsets.UTF_8);
        }

        private static void readHttpRequest(DataInputStream input) throws IOException {
            StringBuilder request = new StringBuilder();
            int matched = 0;
            while (matched < 4) {
                int value = input.read();
                if (value < 0) {
                    throw new EOFException("HTTP request was not received");
                }
                request.append((char) value);
                int expected = switch (matched) {
                    case 0, 2 -> '\r';
                    default -> '\n';
                };
                matched = value == expected ? matched + 1 : 0;
            }
            assertThat(request.toString()).startsWith("GET /media HTTP/1.1");
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            executor.shutdownNow();
        }
    }
}
