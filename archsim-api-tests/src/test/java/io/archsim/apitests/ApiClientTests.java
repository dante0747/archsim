package io.archsim.apitests;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

	// -- cookies

	@Test
	void get_answerSettingCookies_keepsThem() {
		this.client.get("/in");

		assertThat(this.client.cookies()).containsEntry("__Host-a", "1").containsEntry("b", "2");
	}

	@Test
	void get_withCookiesHeld_sendsThemAllEvenSecureOnesOverHttp() {
		this.client.get("/in");

		this.client.get("/anything");

		assertThat(this.lastCookie.get()).isEqualTo("__Host-a=1; b=2");
	}

	@Test
	void get_answerExpiringCookie_forgetsIt() {
		this.client.get("/in");

		this.client.get("/out");

		assertThat(this.client.cookies()).containsOnlyKeys("b");
	}

	@Test
	void cookie_held_returnsItsValue() {
		this.client.get("/in");

		Optional<String> cookie = this.client.cookie("b");

		assertThat(cookie).hasValue("2");
	}

	@Test
	void cookie_notHeld_isEmpty() {
		Optional<String> cookie = this.client.cookie("b");

		assertThat(cookie).isEmpty();
	}

	@Test
	void setCookie_ofCookieTheAnswerSets_returnsItsHeader() {
		ApiResponse response = this.client.get("/in");

		Optional<String> setCookie = response.setCookie("__Host-a");

		assertThat(setCookie).hasValueSatisfying((header) -> assertThat(header).contains("Secure"));
	}

	@Test
	void setCookie_ofCookieTheAnswerDoesNotSet_isEmpty() {
		ApiResponse response = this.client.get("/in");

		Optional<String> setCookie = response.setCookie("__Host-b");

		assertThat(setCookie).isEmpty();
	}

	// -- headers and queries

	@Test
	void get_withDefaultHeader_sendsIt() {
		this.client.header("X-Test", "one");

		this.client.get("/q");

		assertThat(this.lastHeader.get()).isEqualTo("one");
	}

	@Test
	void get_withRequestHeaderInAnotherCase_sendsItOverTheDefault() {
		this.client.header("X-Test", "one");

		this.client.get("/q", Map.of("x-test", "two"));

		assertThat(this.lastHeader.get()).isEqualTo("two");
	}

	@Test
	void get_afterDefaultHeaderRemovedInAnotherCase_sendsNone() {
		this.client.header("X-Test", "one");
		this.client.header("x-TEST", null);

		this.client.get("/q");

		assertThat(this.lastHeader.get()).isEmpty();
	}

	@Test
	void header_setInAnotherCase_returnsItsValue() {
		this.client.header("X-Test", "one");

		Optional<String> header = this.client.header("x-test");

		assertThat(header).hasValue("one");
	}

	@Test
	void header_neverSet_isEmpty() {
		Optional<String> header = this.client.header("X-Test");

		assertThat(header).isEmpty();
	}

	@Test
	void query_withNullValue_encodesTheOthersAndLeavesItOut() {
		String path = ApiClient.query("/q", "a", "x y&z", "skipped", null, "n", 3);

		assertThat(path).isEqualTo("/q?a=x+y%26z&n=3");
	}

	@Test
	void get_pathWithQuery_sendsTheQueryAsItIs() {
		this.client.get("/q?a=x+y%26z&n=3");

		assertThat(this.lastQuery.get()).isEqualTo("a=x+y%26z&n=3");
	}

	// -- answers

	@Test
	void json_ofJsonBody_parsesIt() {
		ApiResponse response = this.client.get("/q");

		boolean ok = response.json().path("ok").asBoolean();

		assertThat(ok).isTrue();
	}

	@Test
	void expectStatus_ofThatStatus_returnsTheAnswer() {
		ApiResponse response = this.client.get("/q");

		ApiResponse expected = response.expectStatus(200);

		assertThat(expected).isSameAs(response);
	}

	@Test
	void expectStatus_ofAnotherStatus_failsNamingTheExpectedOne() {
		ApiResponse response = this.client.get("/q");

		assertThatThrownBy(() -> response.expectStatus(201)).isInstanceOf(AssertionError.class)
			.hasMessageStartingWith("expected 201: GET /api/v1/q -> 200");
	}

	// -- redirects

	@Test
	void open_urlOnThePublicAddress_getsItFromThisServer() {
		ApiResponse response = this.client.open("https://archsim.test/api/v1/q?x=1");

		assertThat(response.status()).isEqualTo(200);
		assertThat(this.lastQuery.get()).isEqualTo("x=1");
	}

	@Test
	void open_absolutePath_getsItFromThisServer() {
		ApiResponse response = this.client.open("/api/v1/q");

		assertThat(response.status()).isEqualTo(200);
	}

	@Test
	void open_urlOfAnotherSite_fails() {
		assertThatThrownBy(() -> this.client.open("https://evil.example/")).isInstanceOf(AssertionError.class);
	}

	@Test
	void open_protocolRelativeUrl_fails() {
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
