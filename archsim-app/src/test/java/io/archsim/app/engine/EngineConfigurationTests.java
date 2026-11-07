package io.archsim.app.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.app.web.ApiException;
import io.archsim.engine.simulation.SimulationLimits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The engine service's client as the application configures it: HTTP/1.1, which uvicorn
 * reads the body of, a read timeout that answers {@code 503}, and a call sent once more
 * when the service drops its connection before answering.
 */
class EngineConfigurationTests {

	private static final SimulationLimits LIMITS = new SimulationLimits(1, 1);

	private final AtomicReference<@Nullable String> upgrade = new AtomicReference<>();

	private final AtomicInteger requests = new AtomicInteger();

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

		simulate(engine);

		assertThat(this.upgrade.get()).isNull();
	}

	@Test
	void engineService_answerSlowerThanTheTimeout_isServiceUnavailableSayingSo() throws IOException {
		EngineService engine = engine(Duration.ofSeconds(3), Duration.ofSeconds(1));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> simulate(engine)).satisfies((ex) -> {
			assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			assertThat(ex.getMessage()).startsWith("The simulation engine took too long to answer");
		});
	}

	@Test
	void engineService_connectionDroppedBeforeTheAnswer_sendsTheCallOnceMore() throws IOException {
		EngineService engine = droppingFirst(1);

		JsonNode report = simulate(engine);

		assertThat(report).isEqualTo(JsonNodeFactory.instance.objectNode());
		assertThat(this.requests).hasValue(2);
	}

	@Test
	void engineService_connectionDroppedAgain_isServiceUnavailable() throws IOException {
		EngineService engine = droppingFirst(2);

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> simulate(engine)).satisfies((ex) -> {
			assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			assertThat(ex.getMessage()).startsWith("The simulation engine is not available right now");
		});
		assertThat(this.requests).hasValue(2);
	}

	private static JsonNode simulate(EngineService engine) {
		return engine.simulate(JsonNodeFactory.instance.objectNode(), LIMITS);
	}

	private EngineService engine(Duration delay, Duration timeout) throws IOException {
		return engine(delay, timeout, 0);
	}

	/**
	 * The client of a stand-in service.
	 * @param delay how long the service takes to answer
	 * @param timeout how long the client waits for an answer
	 * @param drops how many requests the service drops, closing their connection without
	 * an answer, before it answers
	 * @return the client
	 */
	private EngineService engine(Duration delay, Duration timeout, int drops) throws IOException {
		HttpServer stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		stub.createContext("/", (exchange) -> answer(exchange, delay, drops));
		stub.start();
		this.server = stub;
		URI url = URI.create("http://127.0.0.1:" + stub.getAddress().getPort());
		return new EngineConfiguration().engineService(new EngineServiceProperties(url, timeout));
	}

	/**
	 * The client of a stand-in service that drops its first requests, closing their
	 * connection without an answer, and answers the rest at once.
	 * @param drops how many requests it drops
	 * @return the client
	 */
	private EngineService droppingFirst(int drops) throws IOException {
		return engine(Duration.ZERO, Duration.ofSeconds(5), drops);
	}

	private void answer(HttpExchange exchange, Duration delay, int drops) throws IOException {
		this.upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
		exchange.getRequestBody().readAllBytes();
		if (this.requests.incrementAndGet() <= drops) {
			// closing the exchange before answering closes its connection, as uvicorn
			// closes one left idle for its keep-alive just as the client reuses it
			exchange.close();
			return;
		}
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
