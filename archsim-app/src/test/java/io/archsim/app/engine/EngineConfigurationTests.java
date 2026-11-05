package io.archsim.app.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The engine service's client as the application configures it: HTTP/1.1, which uvicorn
 * reads the body of, and a read timeout that answers {@code 503}.
 */
class EngineConfigurationTests {

	private final AtomicReference<@Nullable String> upgrade = new AtomicReference<>();

	private @Nullable HttpServer server;

	@AfterEach
	void stop() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	@Test
	void engineService_request_asksForNoProtocolUpgrade() throws IOException {
		EngineService engine = engine(Duration.ZERO, Duration.ofSeconds(5));

		engine.export(JsonMapper.builder().build().createObjectNode(), "mermaid");

		assertThat(this.upgrade.get()).isNull();
	}

	@Test
	void engineService_answerSlowerThanTheTimeout_isServiceUnavailableSayingSo() throws IOException {
		EngineService engine = engine(Duration.ofSeconds(3), Duration.ofSeconds(1));

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> engine.export(JsonMapper.builder().build().createObjectNode(), "mermaid"))
			.satisfies((ex) -> {
				assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
				assertThat(ex.getMessage()).startsWith("The simulation engine took too long to answer");
			});
	}

	private EngineService engine(Duration delay, Duration timeout) throws IOException {
		HttpServer stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		stub.createContext("/", (exchange) -> answer(exchange, delay));
		stub.start();
		this.server = stub;
		URI url = URI.create("http://127.0.0.1:" + stub.getAddress().getPort());
		return new EngineConfiguration().engineService(new EngineServiceProperties(url, timeout));
	}

	private void answer(HttpExchange exchange, Duration delay) throws IOException {
		this.upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
		exchange.getRequestBody().readAllBytes();
		try {
			Thread.sleep(delay);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

}
