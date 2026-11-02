package io.archsim.apitests;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client against a tiny server that sets cookies and records what it was sent. */
class ApiClientTests {

	private final AtomicReference<String> lastCookie = new AtomicReference<>("");

	private final AtomicReference<String> lastHeader = new AtomicReference<>("");

	private final AtomicReference<String> lastQuery = new AtomicReference<>("");

	private final HttpServer server = start();

	private final ApiClient client = new ApiClient(URI.create("http://127.0.0.1:" + this.server.getAddress().getPort()),
			"https://archsim.test");

	@AfterEach
	void stop() {
		this.server.stop(0);
	}

	@Test
	void get_answersSettingAndExpiringCookies_keepsSendsAndForgetsThemLikeABrowser() {
		ApiResponse in = this.client.get("/in");
		assertThat(in.setCookie("__Host-a")).contains("Secure");
		assertThat(this.client.cookies()).containsEntry("__Host-a", "1").containsEntry("b", "2");
		this.client.get("/anything");
		// Secure cookies too, over HTTP
		assertThat(this.lastCookie.get()).isEqualTo("__Host-a=1; b=2");
		this.client.get("/out");
		assertThat(this.client.cookies()).containsOnlyKeys("b");
	}

	@Test
	void get_withDefaultAndRequestHeaders_sendsHeadersAndEncodesQueries() {
		this.client.header("X-Test", "one");
		this.client.get(ApiClient.query("/q", "a", "x y&z", "skipped", null, "n", 3));
		assertThat(this.lastHeader.get()).isEqualTo("one");
		assertThat(this.lastQuery.get()).isEqualTo("a=x+y%26z&n=3");
		this.client.get("/q", Map.of("x-test", "two")); // per request, whatever the case
		assertThat(this.lastHeader.get()).isEqualTo("two");
		this.client.header("x-TEST", null);
		this.client.get("/q");
		assertThat(this.lastHeader.get()).isEmpty();
		assertThat(this.client.get("/q").json().path("ok").asBoolean()).isTrue();
	}

	@Test
	void open_redirectLocation_followsOnlyThisServerOrItsPublicAddress() {
		assertThat(this.client.open("https://archsim.test/api/v1/q?x=1").status()).isEqualTo(200);
		assertThat(this.lastQuery.get()).isEqualTo("x=1");
		assertThat(this.client.open("/api/v1/q").status()).isEqualTo(200);
		assertThatThrownBy(() -> this.client.open("https://evil.example/")).isInstanceOf(AssertionError.class);
		assertThatThrownBy(() -> this.client.open("//evil.example/x")).isInstanceOf(AssertionError.class);
	}

	private HttpServer start() {
		try {
			HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			httpServer.createContext("/", this::answer);
			httpServer.start();
			return httpServer;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void answer(HttpExchange exchange) throws IOException {
		try (exchange) {
			recordRequest(exchange);
			setCookies(exchange);
			byte[] body = "{\"ok\": true}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
		}
	}

	private void recordRequest(HttpExchange exchange) {
		this.lastCookie.set(orEmpty(exchange.getRequestHeaders().getFirst("Cookie")));
		this.lastHeader.set(orEmpty(exchange.getRequestHeaders().getFirst("X-Test")));
		this.lastQuery.set(orEmpty(exchange.getRequestURI().getRawQuery()));
	}

	private static void setCookies(HttpExchange exchange) {
		switch (exchange.getRequestURI().getPath()) {
			case "/api/v1/in" -> exchange.getResponseHeaders()
				.put("Set-Cookie", List.of("__Host-a=1; HttpOnly; Path=/; SameSite=lax; Secure", "b=2; Max-Age=600"));
			case "/api/v1/out" -> exchange.getResponseHeaders()
				.put("Set-Cookie", List.of("__Host-a=\"\"; expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"));
			default -> {
			}
		}
	}

	private static String orEmpty(@Nullable String value) {
		return (value != null) ? value : "";
	}

}
