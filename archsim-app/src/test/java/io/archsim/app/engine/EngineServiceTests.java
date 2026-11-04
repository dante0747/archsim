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

import io.archsim.app.config.CalibrationProperties.Outbound;
import io.archsim.app.limits.WorkspaceLimits.RunLimits;
import io.archsim.app.web.ApiException;
import io.archsim.app.web.JsonText;
import io.archsim.model.codec.BindingException;
import io.archsim.model.issue.Issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The engine service's client, against a stand-in service on a free port.
 */
class EngineServiceTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNode DESIGN = JSON.readTree("{\"kind\": \"archsim.architecture\"}");

	private static final OutboundPolicy POLICY = new OutboundPolicy(Outbound.ALLOWLIST, List.of("prom:9090"), 30.0,
			1024);

	private static final RunLimits REPLAY = new RunLimits(1000, 20.0);

	private final AtomicReference<String> received = new AtomicReference<>();

	private @Nullable HttpServer server;

	@AfterEach
	void stop() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	@Test
	void analyze_answer_isPassedOnAsWritten() throws IOException {
		EngineService engine = serving(200, "{\"peakRps\":600,\"tiny\":1e-05}");

		JsonText answer = engine.analyze(DESIGN, "three-api-replicas");

		assertThat(answer.json()).isEqualTo("{\"peakRps\":600,\"tiny\":1e-05}");
	}

	@Test
	void analyze_request_carriesTheDesignAndScenario() throws IOException {
		EngineService engine = serving(200, "{}");

		engine.analyze(DESIGN, null);

		assertThat(JSON.readTree(this.received.get()))
			.isEqualTo(JSON.readTree("{\"document\": {\"kind\": \"archsim.architecture\"}, \"scenarioId\": null}"));
	}

	@Test
	void analyze_designTheEngineCannotRead_throwsItsIssues() throws IOException {
		EngineService engine = serving(422,
				"{\"issues\": [{\"severity\": \"error\", \"code\": \"document.schema\", \"message\": \"bad\"}]}");

		assertThatExceptionOfType(EngineRefusalException.class).isThrownBy(() -> engine.analyze(DESIGN, null))
			.extracting(EngineRefusalException::issues)
			.isEqualTo(List.of(Issue.error("document.schema", "bad")));
	}

	@Test
	void analyze_serviceDown_isServiceUnavailable() throws IOException {
		EngineService engine = new EngineService(RestClient.create("http://127.0.0.1:" + freePort()));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> engine.analyze(DESIGN, null))
			.extracting(ApiException::status)
			.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void prepare_answer_isTheRunToQueue() throws IOException {
		EngineService engine = serving(200, """
				{"scenarioId": "peak", "scenarioName": "Peak", "seed": 7, "document": {"kind": "archsim.architecture"},
				 "quickCheck": {"eligible": false, "reason": "the traffic is not constant", "requestedS": 60.0,
				                "simulatedS": null, "sampleS": null}}""");

		PreparedRun prepared = engine.prepare(new PrepareRequest(DESIGN, "peak", 1000, 20.0, 7L));

		assertThat(prepared.scenarioName()).isEqualTo("Peak");
		assertThat(prepared.seed()).isEqualTo(7);
		assertThat(prepared.quickCheck().reason()).isEqualTo("the traffic is not constant");
	}

	@Test
	void prepare_request_carriesTheLimitsAndSeed() throws IOException {
		EngineService engine = serving(200, """
				{"scenarioId": null, "scenarioName": null, "seed": 1, "document": {},
				 "quickCheck": {"eligible": true, "reason": "", "requestedS": 60.0, "simulatedS": 30.0,
				                "sampleS": 20.0}}""");

		engine.prepare(new PrepareRequest(DESIGN, null, 1000, 20.0, null));

		assertThat(JSON.readTree(this.received.get())).isEqualTo(JSON.readTree("""
				{"document": {"kind": "archsim.architecture"}, "scenarioId": null, "maxRequests": 1000,
				 "maxWallSeconds": 20.0, "seed": null}"""));
	}

	@Test
	void prepare_runOverItsLimits_throwsTheEnginesWords() throws IOException {
		EngineService engine = serving(422,
				"{\"message\": \"too many requests\", \"issues\": [], \"kind\": \"limit\"}");

		assertThatExceptionOfType(EngineRefusalException.class)
			.isThrownBy(() -> engine.prepare(new PrepareRequest(DESIGN, null, 1000, 20.0, null)))
			.satisfies((ex) -> {
				assertThat(ex.overLimits()).isTrue();
				assertThat(ex.engineMessage()).isEqualTo("too many requests");
			});
	}

	@Test
	void compare_request_carriesBothReportsAndTheirNames() throws IOException {
		EngineService engine = serving(200, "{\"kpis\": []}");

		engine.compare(JSON.readTree("{\"summary\": 1}"), JSON.readTree("{\"summary\": 2}"), "before", "after");

		assertThat(JSON.readTree(this.received.get())).isEqualTo(JSON.readTree(
				"{\"a\": {\"summary\": 1}, \"b\": {\"summary\": 2}, \"labelA\": \"before\", \"labelB\": \"after\"}"));
	}

	@Test
	void prepare_answerMissingTheSeed_failsAtOnce() throws IOException {
		EngineService engine = serving(200, """
				{"scenarioId": null, "scenarioName": null, "document": {},
				 "quickCheck": {"eligible": true, "reason": "", "requestedS": 60.0, "simulatedS": 30.0,
				                "sampleS": 20.0}}""");

		assertThatExceptionOfType(BindingException.class)
			.isThrownBy(() -> engine.prepare(new PrepareRequest(DESIGN, null, 1000, 20.0, null)))
			.satisfies((ex) -> assertThat(ex.errors()).extracting((error) -> error.location().parts())
				.contains(List.of("seed")));
	}

	@Test
	void summarizeSeeds_request_carriesTheRunsAndObjectives() throws IOException {
		EngineService engine = serving(200, "{}");

		engine.summarizeSeeds(JSON.createArrayNode().add(JSON.createObjectNode().put("seed", 42).putNull("summary")),
				JSON.readTree("{\"latencyP95Ms\": 300.0}"));

		assertThat(JSON.readTree(this.received.get())).isEqualTo(JSON
			.readTree("{\"runs\": [{\"seed\": 42, \"summary\": null}], \"objectives\": {\"latencyP95Ms\": 300.0}}"));
	}

	@Test
	void export_request_carriesTheDesignAndFormat() throws IOException {
		EngineService engine = serving(200, "{}");

		engine.export(DESIGN, "mermaid");

		assertThat(JSON.readTree(this.received.get()))
			.isEqualTo(JSON.readTree("{\"document\": {\"kind\": \"archsim.architecture\"}, \"format\": \"mermaid\"}"));
	}

	@Test
	void importDesign_request_carriesTheTextAndName() throws IOException {
		EngineService engine = serving(200, "{}");

		engine.importDesign("compose", "services: {}", null);

		assertThat(JSON.readTree(this.received.get()))
			.isEqualTo(JSON.readTree("{\"format\": \"compose\", \"text\": \"services: {}\", \"name\": null}"));
	}

	@Test
	void calibrate_somethingItCannotUse_throwsTheApisWords() throws IOException {
		EngineService engine = serving(422, """
				{"message": "Give a Prometheus URL or recorded responses", "issues": [], "kind": "unreadable"}""");

		assertThatExceptionOfType(EngineRefusalException.class)
			.isThrownBy(() -> engine.calibrate(JSON.createObjectNode(), POLICY, REPLAY))
			.satisfies((refusal) -> {
				assertThat(refusal.answer("x").getMessage()).isEqualTo("Give a Prometheus URL or recorded responses");
				assertThat(refusal.answer("x").issues()).isEmpty();
			});
	}

	@Test
	void calibrate_request_carriesThePolicyOverWhatTheRequestSays() throws IOException {
		EngineService engine = serving(200, "{}");

		engine.calibrate((ObjectNode) JSON.readTree("{\"source\": \"otlp\", \"outbound\": \"open\"}"), POLICY, REPLAY);

		assertThat(JSON.readTree(this.received.get())).isEqualTo(JSON.readTree("""
				{"source": "otlp", "outbound": "allowlist", "allowedHosts": ["prom:9090"], "timeoutS": 30.0,
				 "maxBytes": 1024, "maxRequests": 1000, "maxWallSeconds": 20.0}"""));
	}

	private EngineService serving(int status, String answer) throws IOException {
		HttpServer stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		stub.createContext("/", (exchange) -> answer(exchange, status, answer));
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
