package io.archsim.benchmarks.docker;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The services' endpoints over HTTP, against a server on this machine: JSON read as
 * Python reads it, and an error status refused as {@code urllib} refused it, so that a
 * wait for a service tries again.
 */
class HttpServicesTests {

	private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

	private HttpServer server;

	@BeforeEach
	void start() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		this.server.createContext("/stats", (exchange) -> answer(exchange, 200, "{\"requests\": 3, \"cpuMs\": 1.5}"));
		this.server.createContext("/reset", (exchange) -> answer(exchange, 200, "{}"));
		this.server.createContext("/health", (exchange) -> answer(exchange, 503, "{}"));
		this.server.start();
	}

	@AfterEach
	void stop() {
		this.server.stop(0);
	}

	@Test
	void get_anEndpoint_isItsJsonAsPythonReadsIt() {
		assertThat(new HttpServices().get(url("/stats"))).isEqualTo(JsonText.read("{\"requests\": 3, \"cpuMs\": 1.5}"));
	}

	@Test
	void post_anEndpoint_sendsNoBody() {
		new HttpServices().post(url("/reset"));

		assertThat(this.requests).containsExactly("POST /reset 0 bytes HTTP/1.1");
	}

	// what makes a wait for a service try again, as urllib's HTTPError did
	@Test
	void get_anEndpointAnsweringAnError_isRefused() {
		Throwable thrown = catchThrowable(() -> new HttpServices().get(url("/health")));

		assertThat(thrown).isInstanceOf(UncheckedIOException.class).hasMessageContaining("answered 503");
	}

	// what a wait for a starting stack meets most
	@Test
	void get_noServer_isRefused() {
		String url = url("/health");
		this.server.stop(0);

		Throwable thrown = catchThrowable(() -> new HttpServices().get(url));

		assertThat(thrown).isInstanceOf(UncheckedIOException.class);
	}

	private String url(String path) {
		return "http://127.0.0.1:" + this.server.getAddress().getPort() + path;
	}

	// records the request: its method, path, body's length and protocol
	private void answer(HttpExchange exchange, int status, String body) throws IOException {
		byte[] sent = exchange.getRequestBody().readAllBytes();
		this.requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + sent.length + " bytes "
				+ exchange.getProtocol());
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

}
