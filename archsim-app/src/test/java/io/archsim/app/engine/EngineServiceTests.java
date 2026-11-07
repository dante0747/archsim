package io.archsim.app.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.web.ApiException;
import io.archsim.engine.run.SimulationLimitException;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.model.issue.Issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The engine service's client, against a stand-in service on a free port: its one call,
 * the simulation a calibration replays.
 */
class EngineServiceTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final SimulationLimits LIMITS = new SimulationLimits(1000, 20.0);

	private static final ObjectNode DESIGN = (ObjectNode) JSON.readTree("{\"metadata\": {\"name\": \"Shop\"}}");

	private final AtomicReference<String> received = new AtomicReference<>();

	private @Nullable HttpServer server;

	@AfterEach
	void stop() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	@Test
	void simulate_answer_isTheReport() throws IOException {
		EngineService engine = serving(200, "{\"summary\": {\"throughputRps\": 1e-05}, \"nodes\": []}");

		JsonNode report = engine.simulate(DESIGN, LIMITS);

		assertThat(report).isEqualTo(JSON.readTree("{\"summary\": {\"throughputRps\": 1e-05}, \"nodes\": []}"));
	}

	@Test
	void simulate_request_carriesTheDesignAndTheLimits() throws IOException {
		EngineService engine = serving(200, "{}");

		engine.simulate(DESIGN, LIMITS);

		assertThat(JSON.readTree(this.received.get())).isEqualTo(JSON.readTree("""
				{"document": {"metadata": {"name": "Shop"}}, "maxRequests": 1000, "maxWallSeconds": 20.0}"""));
	}

	@Test
	void simulate_runOverItsLimits_throwsTheEnginesWords() throws IOException {
		EngineService engine = serving(422, """
				{"message": "too many requests (1,500,000 > 1,000)", "issues": [], "kind": "limit"}""");

		assertThatExceptionOfType(SimulationLimitException.class).isThrownBy(() -> engine.simulate(DESIGN, LIMITS))
			.withMessage("too many requests (1,500,000 > 1,000)");
	}

	@Test
	void simulate_designThatIsNotValid_throwsItsIssues() throws IOException {
		EngineService engine = serving(422, """
				{"message": "bad", "kind": "invalid",
				 "issues": [{"severity": "error", "code": "document.schema", "message": "bad"}]}""");

		assertThatExceptionOfType(EngineRefusalException.class).isThrownBy(() -> engine.simulate(DESIGN, LIMITS))
			.extracting(EngineRefusalException::issues)
			.isEqualTo(List.of(Issue.error("document.schema", "bad")));
	}

	@Test
	void simulate_serviceDown_isServiceUnavailable() throws IOException {
		EngineService engine = new EngineService(RestClient.create("http://127.0.0.1:" + freePort()));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> engine.simulate(DESIGN, LIMITS))
			.extracting(ApiException::status)
			.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	private EngineService serving(int status, String answer) throws IOException {
		HttpServer stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		stub.createContext("/simulate", (exchange) -> answer(exchange, status, answer));
		stub.start();
		this.server = stub;
		return new EngineService(RestClient.create("http://127.0.0.1:" + stub.getAddress().getPort()));
	}

	private void answer(HttpExchange exchange, int status, String answer) throws IOException {
		this.received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		byte[] body = answer.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

}
