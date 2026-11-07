package io.archsim.engine.calibration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.otlp.CpuUnit;
import io.archsim.engine.calibration.otlp.OtlpMapping;
import io.archsim.engine.calibration.otlp.OtlpMapping.CpuAttribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Calibrations from traces where the golden files do not reach: no spans, services that
 * match no part, too few spans, a mapping that names a part the design lacks, and the
 * workload the replay runs. Every expected note and value is the Python engine's on the
 * same design and traces.
 */
class OtlpCalibrationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Three SERVER spans of {@code checkout}, a second apart, 5 ms each. */
	private static final JsonNode TRACES = JSON.readTree("""
			{"resourceSpans": [{
			  "resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "checkout"}}]},
			  "scopeSpans": [{"spans": [
			    {"traceId": "t", "spanId": "s0", "kind": 2, "startTimeUnixNano": "0", "endTimeUnixNano": "5000000"},
			    {"traceId": "t", "spanId": "s1", "kind": 2, "startTimeUnixNano": "1000000000",
			     "endTimeUnixNano": "1005000000"},
			    {"traceId": "t", "spanId": "s2", "kind": 2, "startTimeUnixNano": "2000000000",
			     "endTimeUnixNano": "2005000000"}]}]}]}""");

	private static final OtlpMapping CHECKOUT_IS_API = new OtlpMapping(Map.of("checkout", "api"), Map.of(),
			new CpuAttribute("cpu", CpuUnit.MILLISECONDS));

	/** The designs the calibration replayed, in order. */
	private final List<ObjectNode> replayed = new ArrayList<>();

	private final OtlpCalibration calibration = new OtlpCalibration((document, limits) -> {
		this.replayed.add(document.deepCopy());
		return JSON.readTree("{\"nodes\": [], \"provenance\": {\"resultDigest\": \"d\"}}");
	}, Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC));

	@Test
	void calibrate_noSpans_leavesTheDesignAsItWasAndSaysSo() {
		CalibrationOutcome outcome = calibrate(StarterDesign.read(), JSON.readTree("{\"resourceSpans\": []}"), null);

		assertThat(outcome.notes()).containsExactly("the file has no spans");
		assertThat(outcome.changes()).isEmpty();
		assertThat(outcome.toJson().path("calibration").isNull()).isTrue();
	}

	@Test
	void calibrate_serviceThatMatchesNoPart_isNotedAndTheReplayStillRuns() {
		CalibrationOutcome outcome = calibrate(StarterDesign.read(), TRACES, null);

		assertThat(outcome.notes()).containsExactly(
				"service 'checkout' (3 spans) matches no service part: map it in the mapping's 'services'");
		JsonNode record = outcome.document().path("calibration");
		assertThat(record.path("timeRange").asString()).isEqualTo("1970-01-01T00:00:00Z/1970-01-01T00:00:02Z");
		assertThat(record.path("replayDigest").asString()).isEqualTo("d");
		assertThat(record.path("inputs").intValue()).isZero();
	}

	@Test
	void calibrate_tooFewSpans_leavesTheServiceTimeAndSaysWhy() {
		CalibrationOutcome outcome = calibrate(StarterDesign.read(), TRACES, CHECKOUT_IS_API);

		assertThat(outcome.notes()).first()
			.isEqualTo("SERVER spans of checkout, minus their outgoing calls: only 3 spans (need 20); "
					+ "api.serviceTimeMs unchanged");
		assertThat(outcome.changes()).isEmpty();
	}

	@Test
	void calibrate_noSpanCarriesTheCpuAttribute_leavesTheCpuSharesAndSaysWhy() {
		CalibrationOutcome outcome = calibrate(StarterDesign.read(), TRACES, CHECKOUT_IS_API);

		assertThat(outcome.notes()).last()
			.isEqualTo("no SERVER span carries the CPU-time attribute 'cpu': CPU shares unchanged");
	}

	@Test
	void calibrate_mappingNamingAPartTheDesignLacks_isPythonsKeyError() {
		OtlpMapping ghost = new OtlpMapping(Map.of("checkout", "ghost"), Map.of(), null);

		assertThatIllegalArgumentException().isThrownBy(() -> calibrate(StarterDesign.read(), TRACES, ghost))
			.withMessage("'ghost'");
	}

	@ParameterizedTest
	@CsvSource({ "600, 100, 300, 30", "600, 10, 300, 10", "30, 5, 60, 5" })
	void calibrate_design_isReplayedAtTheTracedRateForAtLeastAMinuteAndAtMostFive(double durationS, double warmupS,
			double replayedDurationS, double replayedWarmupS) {
		ObjectNode design = (ObjectNode) StarterDesign.read();
		design.withObjectProperty("workload").put("durationS", durationS).put("warmupS", warmupS);

		calibrate(design, TRACES, null);

		assertThat(this.replayed).singleElement().satisfies((document) -> {
			JsonNode workload = document.path("workload");
			assertThat(workload.path("rps").doubleValue()).isEqualTo(1.4963);
			assertThat(workload.path("pattern")).isEqualTo(JSON.readTree("{\"kind\": \"constant\"}"));
			assertThat(workload.path("durationS").doubleValue()).isEqualTo(replayedDurationS);
			assertThat(workload.path("warmupS").doubleValue()).isEqualTo(replayedWarmupS);
		});
	}

	private CalibrationOutcome calibrate(JsonNode design, JsonNode traces, @Nullable OtlpMapping mapping) {
		return this.calibration
			.calibrate(new OtlpRequest(design, traces, mapping, OtlpRequest.DEFAULT_MIN_SAMPLES, null));
	}

}
