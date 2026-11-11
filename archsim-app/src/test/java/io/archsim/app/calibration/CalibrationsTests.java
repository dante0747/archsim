package io.archsim.app.calibration;

import java.util.List;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.apitests.Examples;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.support.HostedTestSupport;
import io.archsim.app.support.UsedUpLimits;
import io.archsim.engine.calibration.ReplaySimulator;
import io.archsim.engine.run.SimulationLimitException;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * Calibrations on a hosted server, in Java: who may run them, how many an hour, the
 * replay the simulator runs within the workspace's limits (stood in for here), and every
 * refusal in the words the Python API answered. Rate limits are on.
 */
@TestPropertySource(properties = { "archsim.rate-limits=true", "archsim.prometheus-allowed-hosts=prom:9090" })
class CalibrationsTests extends HostedTestSupport {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Three SERVER spans of {@code checkout}, a second apart: a service no part matches.
	 */
	private static final JsonNode TRACES = JSON.readTree("""
			{"resourceSpans": [{
			  "resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "checkout"}}]},
			  "scopeSpans": [{"spans": [
			    {"traceId": "t", "spanId": "s0", "kind": 2, "startTimeUnixNano": "0", "endTimeUnixNano": "5000000"},
			    {"traceId": "t", "spanId": "s1", "kind": 2, "startTimeUnixNano": "1000000000",
			     "endTimeUnixNano": "1005000000"},
			    {"traceId": "t", "spanId": "s2", "kind": 2, "startTimeUnixNano": "2000000000",
			     "endTimeUnixNano": "2005000000"}]}]}]}""");

	private static final JsonNode NO_SPANS = JSON.readTree("{\"resourceSpans\": []}");

	private static final JsonNode RECORDED = JSON
		.readTree(Examples.text(Examples.fixture("calibration/starter-prometheus.json")));

	private static final String MAPPING = Examples.text(Examples.fixture("calibration/starter-mapping.yaml"));

	@MockitoBean
	private ReplaySimulator replay;

	@Test
	void calibrate_byViewer_isForbidden() {
		SignedIn ada = signedIn("Ada");
		SignedIn viv = signedIn("Viv");
		member(ada.workspaceId(), viv.userId(), "viewer");

		assertThat(calibrate(viv, ada.workspaceId(), otlp(NO_SPANS))).hasStatus(403);
		then(this.replay).shouldHaveNoInteractions();
	}

	@Test
	void calibrate_byViewer_isNotCountedAgainstTheHour() {
		SignedIn ada = signedIn("Ada");
		SignedIn viv = signedIn("Viv");
		member(ada.workspaceId(), viv.userId(), "viewer");

		calibrate(viv, ada.workspaceId(), otlp(NO_SPANS));

		assertThat(this.jdbc()
			.sql("SELECT count(*) FROM rate_limits WHERE key = :key")
			.param("key", "user:" + viv.userId() + ":calibrate")
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	void calibrate_pastTheHourlyLimit_isTooManyRequests() {
		SignedIn ada = signedIn("Ada");
		UsedUpLimits.useUp(jdbc(), "user:" + ada.userId() + ":calibrate", RateLimit.CALIBRATIONS_PER_USER);

		assertThat(calibrate(ada, ada.workspaceId(), otlp(NO_SPANS))).hasStatus(429)
			.containsHeader(HttpHeaders.RETRY_AFTER)
			.bodyJson()
			.extractingPath("$.detail.message")
			.asString()
			.startsWith("You have run many calibrations this hour.");
	}

	@Test
	void calibrate_tracesWithoutSpans_answersTheDesignUnchangedWithoutAReplay() {
		SignedIn ada = signedIn("Ada");

		MvcTestResult result = calibrate(ada, ada.workspaceId(), otlp(NO_SPANS));

		assertThat(result).hasStatus(200);
		JsonNode answer = json(result);
		assertThat(answer.path("notes")).isEqualTo(JSON.readTree("[\"the file has no spans\"]"));
		assertThat(answer.path("changes")).isEmpty();
		assertThat(answer.path("calibration").isNull()).isTrue();
		assertThat(answer.path("summary").isNull()).isTrue();
		then(this.replay).shouldHaveNoInteractions();
	}

	@Test
	void calibrate_replay_isSimulatedWithinTheWorkspacesLimits() {
		SignedIn ada = signedIn("Ada");
		given(this.replay.simulate(any(), any()))
			.willReturn(JSON.readTree("{\"nodes\": [], \"provenance\": {\"resultDigest\": \"d\"}}"));

		MvcTestResult result = calibrate(ada, ada.workspaceId(), otlp(TRACES));

		assertThat(result).hasStatus(200);
		JsonNode answer = json(result);
		assertThat(answer.path("notes").get(0).asString())
			.isEqualTo("service 'checkout' (3 spans) matches no service part: map it in the mapping's 'services'");
		assertThat(answer.path("document").path("calibration").path("replayDigest").asString()).isEqualTo("d");
		then(this.replay).should().simulate(any(), eq(new SimulationLimits(2_000_000, 20.0)));
	}

	@Test
	void calibrate_recordedPrometheus_answersTheCalibratedDesign() {
		SignedIn ada = signedIn("Ada");

		MvcTestResult result = calibrate(ada, ada.workspaceId(), prometheus("mapping", MAPPING, "recorded", RECORDED,
				"range", "2026-10-20T00:00:00Z/2026-10-21T00:00:00Z", "replay", false));

		assertThat(result).hasStatus(200);
		JsonNode answer = json(result);
		assertThat(answer.path("changes")).hasSize(13);
		assertThat(answer.path("notes").toString()).contains("the recording covers");
		assertThat(answer.path("document").path("provenance").path("nodes/api/serviceTimeMs").path("kind").asString())
			.isEqualTo("measured");
		then(this.replay).shouldHaveNoInteractions();
	}

	@Test
	void calibrate_replayOverItsLimits_isRefusedSayingWhatToChange() {
		SignedIn ada = signedIn("Ada");
		given(this.replay.simulate(any(), any()))
			.willThrow(new SimulationLimitException("the run would generate 2,400,000 requests (limit 2,000,000)"));

		assertThat(calibrate(ada, ada.workspaceId(), otlp(TRACES))).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Cannot calibrate: the replay run hit a limit (the run would generate 2,400,000 requests "
					+ "(limit 2,000,000)). Shorten the design's durationS, lower the load, "
					+ "or calibrate with replay off.");
	}

	@Test
	void calibrate_designTheEngineCannotRead_isRefusedWithItsIssues() {
		SignedIn ada = signedIn("Ada");
		ObjectNode body = otlp(JSON.createObjectNode());
		body.set("document", JSON.readTree("{\"kind\": \"nope\"}"));

		MvcTestResult result = calibrate(ada, ada.workspaceId(), body);

		assertThat(result).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("The document cannot be calibrated");
		assertThat(json(result).path("detail").path("issues")).isNotEmpty();
	}

	@Test
	void calibrate_replayTheSimulatorCannotRead_isRefusedWithItsIssues() {
		SignedIn ada = signedIn("Ada");
		given(this.replay.simulate(any(), any()))
			.willThrow(new DocumentInvalidException(List.of(Issue.error("document.schema", "bad"))));

		MvcTestResult result = calibrate(ada, ada.workspaceId(), otlp(TRACES));

		assertThat(result).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("The document cannot be calibrated");
		assertThat(result).bodyJson().extractingPath("$.detail.issues[0].code").isEqualTo("document.schema");
	}

	@ParameterizedTest
	@MethodSource("unusable")
	void calibrate_withoutWhatItNeeds_isRefusedInTheApisWords(ObjectNode body, String message) {
		SignedIn ada = signedIn("Ada");

		assertThat(calibrate(ada, ada.workspaceId(), body)).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo(message);
		then(this.replay).shouldHaveNoInteractions();
	}

	static Stream<Arguments> unusable() {
		return Stream.of(Arguments.of(prometheus(), "A Prometheus calibration needs a mapping"),
				Arguments.of(prometheus("mapping", "nodes: {}"), "Give a Prometheus URL or recorded responses"),
				Arguments.of(prometheus("mapping", "nodes: {}", "prometheusUrl", ""),
						"Give a Prometheus URL or recorded responses"),
				Arguments.of(prometheus("mapping", "nodes: {}", "prometheusUrl", "ftp://prom"),
						"The Prometheus URL must be http(s)://host[:port]"),
				Arguments.of(prometheus("mapping", "nodes: {}", "prometheusUrl", "http://other:9090"),
						"This Prometheus host is not allowed here (ARCHSIM_PROMETHEUS_ALLOWED_HOSTS)"),
				Arguments.of(prometheus("mapping", "nodes: {}", "prometheusUrl", "http://[prom"),
						"Cannot calibrate: Invalid IPv6 URL"),
				Arguments.of(prometheus("mapping", "nodes: {}", "prometheusUrl", "http://prom:SECRET"),
						"Cannot calibrate: the Prometheus URL has an invalid port"),
				Arguments.of(prometheus("mapping", 5), "Cannot calibrate: the mapping must be a YAML or JSON object"),
				Arguments.of(prometheus("mapping", "nodes: {}", "recorded", RECORDED, "range", "2026-10-01"),
						"Cannot calibrate: a range is START/END, e.g. 2026-10-01T00:00:00Z/2026-10-08T00:00:00Z"),
				Arguments.of(prometheus("mapping", "nodes: {}", "recorded", JSON.readTree("{\"responses\": [1]}")),
						"Cannot calibrate: a recording's 'responses' must map each query to its response"),
				Arguments.of(prometheus("mapping", "nodes: {}", "recorded", JSON.readTree("{\"responses\": []}")),
						"Cannot calibrate: no time range: pass one or set 'range' in the mapping"),
				Arguments.of(otlp(null), "An OpenTelemetry calibration needs the traces (OTLP JSON)"),
				Arguments.of(withMapping(otlp(TRACES), "services: {checkout: ghost}"), "Cannot calibrate: 'ghost'"));
	}

	private static ObjectNode prometheus(Object... fields) {
		ObjectNode body = JSON.createObjectNode().put("source", "prometheus");
		body.set("document", Examples.starter());
		for (int i = 0; i < fields.length; i += 2) {
			body.set((String) fields[i], JSON.valueToTree(fields[i + 1]));
		}
		return body;
	}

	private static ObjectNode otlp(@Nullable JsonNode traces) {
		ObjectNode body = JSON.createObjectNode().put("source", "otlp");
		body.set("document", Examples.starter());
		if (traces != null) {
			body.set("traces", traces);
		}
		return body;
	}

	private static ObjectNode withMapping(ObjectNode body, String mapping) {
		return body.put("mapping", mapping);
	}

	private MvcTestResult calibrate(SignedIn person, String workspaceId, ObjectNode body) {
		return send(person, HttpMethod.POST, "/api/v1/calibrate?workspace=" + workspaceId,
				JSON.writeValueAsString(body));
	}

}
