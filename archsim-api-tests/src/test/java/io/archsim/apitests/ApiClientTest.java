package io.archsim.apitests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The client against a tiny server that sets cookies and records what it was sent. */
class ApiClientTest {
    private final AtomicReference<String> lastCookie = new AtomicReference<>("");
    private final AtomicReference<String> lastHeader = new AtomicReference<>("");
    private final AtomicReference<String> lastQuery = new AtomicReference<>("");
    private final HttpServer server = start();
    private final ApiClient client =
            new ApiClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "https://archsim.test");

    private HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            s.createContext("/", exchange -> {
                try (exchange) {
                    String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                    String header = exchange.getRequestHeaders().getFirst("X-Test");
                    String query = exchange.getRequestURI().getRawQuery();
                    lastCookie.set(cookie == null ? "" : cookie);
                    lastHeader.set(header == null ? "" : header);
                    lastQuery.set(query == null ? "" : query);
                    switch (exchange.getRequestURI().getPath()) {
                        case "/api/v1/in" ->
                            exchange.getResponseHeaders()
                                    .put(
                                            "Set-Cookie",
                                            List.of(
                                                    "__Host-a=1; HttpOnly; Path=/; SameSite=lax; Secure",
                                                    "b=2; Max-Age=600"));
                        case "/api/v1/out" ->
                            exchange.getResponseHeaders()
                                    .put(
                                            "Set-Cookie",
                                            List.of("__Host-a=\"\"; expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"));
                        default -> {}
                    }
                    byte[] body = "{\"ok\": true}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void keepsSendsAndForgetsCookiesLikeABrowser() {
        ApiResponse in = client.get("/in");
        assertThat(in.setCookie("__Host-a")).contains("Secure");
        assertThat(client.cookies()).containsEntry("__Host-a", "1").containsEntry("b", "2");
        client.get("/anything");
        assertThat(lastCookie.get()).isEqualTo("__Host-a=1; b=2"); // Secure cookies too, over HTTP
        client.get("/out");
        assertThat(client.cookies()).containsOnlyKeys("b");
    }

    @Test
    void sendsDefaultHeadersAndEncodesQueries() {
        client.header("X-Test", "one");
        client.get(ApiClient.query("/q", "a", "x y&z", "skipped", null, "n", 3));
        assertThat(lastHeader.get()).isEqualTo("one");
        assertThat(lastQuery.get()).isEqualTo("a=x+y%26z&n=3");
        client.get("/q", Map.of("x-test", "two")); // per request, whatever the case
        assertThat(lastHeader.get()).isEqualTo("two");
        client.header("x-TEST", null);
        client.get("/q");
        assertThat(lastHeader.get()).isEmpty();
        assertThat(client.get("/q").json().path("ok").asBoolean()).isTrue();
    }

    @Test
    void opensRedirectsOnThisServerOrItsPublicAddressOnly() {
        assertThat(client.open("https://archsim.test/api/v1/q?x=1").status()).isEqualTo(200);
        assertThat(lastQuery.get()).isEqualTo("x=1");
        assertThat(client.open("/api/v1/q").status()).isEqualTo(200);
        assertThatThrownBy(() -> client.open("https://evil.example/")).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> client.open("//evil.example/x")).isInstanceOf(AssertionError.class);
    }
}
