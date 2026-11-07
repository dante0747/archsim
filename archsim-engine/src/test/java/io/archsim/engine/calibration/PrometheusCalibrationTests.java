package io.archsim.engine.calibration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.mapping.MappingLoader;
import io.archsim.engine.calibration.mapping.TimeRange;
import io.archsim.engine.calibration.prometheus.RecordedPrometheus;
import io.archsim.model.component.standard.StandardComponents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Calibrations from Prometheus where the golden files do not reach: ranges, inputs the
 * design does not have, and CPU without a calibrated workload. Every expected note and
 * value is the Python engine's on the same design and responses.
 */
class PrometheusCalibrationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNode DESIGN = StarterDesign.read();

	private static final String RANGE = "{\"start\": \"2026-10-01T00:00:00Z\", \"end\": \"2026-10-01T00:02:00Z\"}";

	private static final ReplaySimulator NO_SIMULATION = (document, limits) -> {
		throw new AssertionError("the calibration simulated");
	};

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);

	private final PrometheusCalibration calibration = new PrometheusCalibration(StandardComponents.registry(),
			NO_SIMULATION, CLOCK);

	@Test
	void calibrate_noRange_isRefusedInThePythonEnginesWords() {
		assertThatIllegalArgumentException().isThrownBy(() -> calibrate("{\"nodes\": {}}", Map.of(), null, null))
			.withMessage("no time range: pass one or set 'range' in the mapping");
	}

	@Test
	void calibrate_rangeThatEndsBeforeItStarts_isRefused() {
		String backwards = "{\"start\": \"2026-10-01T00:02:00Z\", \"end\": \"2026-10-01T00:00:00Z\"}";

		assertThatIllegalArgumentException().isThrownBy(() -> calibrate("{\"nodes\": {}}", Map.of(), backwards, null))
			.withMessage("the range ends before it starts");
	}

	@Test
	void calibrate_rangeAskedForBesideARecording_isNotUsedAndNoted() {
		CalibrationOutcome outcome = calibrate("{\"nodes\": {}}", Map.of(), RANGE,
				TimeRange.parse("2026-09-01T00:00:00Z/2026-09-02T00:00:00Z", 60));

		assertThat(outcome.notes()).containsExactly("the recording covers 2026-10-01T00:00:00Z/2026-10-01T00:02:00Z; "
				+ "the range 2026-09-01T00:00:00Z/2026-09-02T00:00:00Z was not used");
		assertThat(outcome.document().path("calibration").path("inputs").intValue()).isZero();
	}

	@ParameterizedTest
	@MethodSource("skipped")
	void calibrate_inputTheDesignOrItsDataLacks_isSkippedAndNoted(String mapping, Map<String, JsonNode> responses,
			String note) {
		CalibrationOutcome outcome = calibrate(mapping, responses, RANGE, null);

		assertThat(outcome.notes()).containsExactly(note);
		assertThat(outcome.changes()).isEmpty();
	}

	/**
	 * Each mapping with the responses it reads and the one note the Python engine writes.
	 */
	static Stream<Arguments> skipped() {
		Map<String, JsonNode> gauge = Map.of("g", matrix(0, 3));
		return Stream.of(
				Arguments.of("{\"nodes\": {\"ghost\": {\"x\": {\"gauge\": \"g\"}}}}", gauge,
						"mapping names part 'ghost', which is not in the design: skipped"),
				Arguments.of("{\"nodes\": {\"api\": {\"nope\": {\"gauge\": \"g\"}}}}", gauge,
						"api: 'nope' is not a setting of Service / API Servers: skipped"),
				Arguments.of("{\"nodes\": {\"api\": {\"replicas\": {\"gauge\": \"g\", \"unit\": \"bytes\"}}}}", gauge,
						"api: a unit (bytes) is only for memory and size settings; 'replicas' is not one: skipped"),
				Arguments.of(
						"{\"nodes\": {\"cache\": {\"hitRatio\": {\"ratio\": {\"hits\": \"h\", \"misses\": \"m\"}}}}}",
						Map.of("h", matrix(0, 0), "m", matrix(0, 0)),
						"cache: no hits or misses in the range, so no hit ratio was measured; hitRatio unchanged"),
				Arguments.of("{\"edges\": {\"ghostlink\": {\"latencyMs\": {\"client\": \"a\", \"server\": \"b\"}}}}",
						Map.of(), "mapping names link 'ghostlink', which is not in the design: skipped"),
				Arguments.of("{\"workload\": {\"rps\": {\"rate\": \"rate\"}}}", Map.of("rate", matrix()),
						"the arrival-rate query returned no data: workload unchanged"),
				Arguments.of("{\"nodes\": {\"cache\": {\"cpuPerRequest\": {\"cpu\": \"c\", \"requests\": \"r\"}}}}",
						Map.of("c", matrix(0, 1), "r", matrix(0, 5)),
						"cache: CPU per request sets the CPU share of service time, which Cache does not have: "
								+ "skipped"));
	}

	@Test
	void calibrate_workingSetAgainstAnEmptyQuery_isFittedAgainstNothingAsInPython() {
		String mapping = "{\"nodes\": {\"api\": {\"memoryWorkingSet\": {\"workingSet\": \"w\", \"requests\": \"\"}}}}";

		assertThatIllegalArgumentException()
			.isThrownBy(() -> calibrate(mapping, Map.of("w", matrix(0, 1_048_576)), RANGE, null))
			.withMessage("fit the working set against the request rate, requests held, or both");
	}

	@Test
	void calibrate_replayWithAnEmptyThroughputQuery_checksTheLatencyOnly() {
		JsonNode report = JSON.readTree("""
				{"summary": {"latencyMs": {"p50": 10.0, "p95": 20.0, "p99": 30.0}, "throughputRps": 5.0},
				 "window": {"warmupS": 0, "durationS": 60}, "timeseries": {"bucketS": 1, "t": []},
				 "nodes": [], "provenance": {"resultDigest": "d"}}""");
		JsonNode buckets = JSON.readTree("""
				{"status": "success", "data": {"resultType": "matrix", "result": [
				  {"metric": {"le": "0.01"}, "values": [[0, "50"]]}, {"metric": {"le": "0.02"}, "values": [[0, "90"]]},
				  {"metric": {"le": "+Inf"}, "values": [[0, "100"]]}]}}""");
		PrometheusCalibration replaying = new PrometheusCalibration(StandardComponents.registry(),
				(document, limits) -> report, CLOCK);

		CalibrationOutcome outcome = calibrate(replaying,
				"{\"replay\": {\"endToEnd\": {\"histogram\": \"h\", \"throughput\": \"\"}}}", Map.of("h", buckets),
				RANGE);

		assertThat(outcome.document().path("calibration").path("replay")).isEqualTo(JSON.readTree("""
				[{"scope": "end_to_end", "id": null, "label": "End to end", "metric": "p50", "observed": 10.0,
				  "simulated": 10.0, "error": 0.0},
				 {"scope": "end_to_end", "id": null, "label": "End to end", "metric": "p95", "observed": 20.0,
				  "simulated": 20.0, "error": 0.0},
				 {"scope": "end_to_end", "id": null, "label": "End to end", "metric": "p99", "observed": 20.0,
				  "simulated": 30.0, "error": 0.5}]"""));
	}

	@Test
	void calibrate_gauges_areRoundedForWholeSettingsAndConvertedToTheSettingsUnit() {
		CalibrationOutcome outcome = calibrate("""
				{"nodes": {"api": {"replicas": {"gauge": "g"},
				                   "memoryBaselineMb": {"gauge": "w", "unit": "KiB"}}}}""",
				Map.of("g", matrix(0, 2.5, 60, 3.5), "w", matrix(0, 1536)), RANGE, null);

		assertThat(outcome.changes())
			.extracting(Change::path, (change) -> change.before().toString(), (change) -> change.after().toString())
			.containsExactly(tuple("nodes/api/replicas", "2", "3"),
					tuple("nodes/api/memoryBaselineMb", "256.0", "1.5"));
		assertThat(outcome.document().path("calibration").path("inputs").intValue()).isEqualTo(2);
	}

	@Test
	void calibrate_cpuWithoutTheWorkloadFromTheSameRange_isNotReplayedAndSaysWhy() {
		CalibrationOutcome outcome = calibrate(
				"{\"nodes\": {\"api\": {\"cpuPerRequest\": {\"cpu\": \"c\", \"requests\": \"r\"}}}}",
				Map.of("c", matrix(0, 0.5, 60, 1.0, 120, 1.5), "r", matrix(0, 100, 60, 200, 120, 300)), RANGE, null);

		assertThat(outcome.notes()).containsExactly("CPU and memory are replayed only when the workload is calibrated "
				+ "from the same range (workload.rps): the run's own traffic would not match what they used");
		assertThat(outcome.changes()).singleElement()
			.satisfies((change) -> assertThat(change.after()).isEqualTo(JSON.readTree("0.4917")));
		assertThat(outcome.document().path("calibration").path("replay")).isEmpty();
	}

	private CalibrationOutcome calibrate(String mapping, Map<String, JsonNode> responses,
			@Nullable String recordedRange, @Nullable TimeRange range) {
		return this.calibration.calibrate(new PrometheusRequest(DESIGN, MappingLoader.load(JSON.readTree(mapping)),
				recording(responses, recordedRange), range, true, null));
	}

	private static CalibrationOutcome calibrate(PrometheusCalibration calibration, String mapping,
			Map<String, JsonNode> responses, String recordedRange) {
		return calibration.calibrate(new PrometheusRequest(DESIGN, MappingLoader.load(JSON.readTree(mapping)),
				recording(responses, recordedRange), null, true, null));
	}

	private static RecordedPrometheus recording(Map<String, JsonNode> responses, @Nullable String recordedRange) {
		ObjectNode recording = JSON.createObjectNode().put("source", "test");
		recording.putObject("responses").setAll(responses);
		if (recordedRange != null) {
			recording.set("range", JSON.readTree(recordedRange));
		}
		return RecordedPrometheus.of(recording, "");
	}

	/**
	 * A matrix of one series.
	 * @param points each point's time and value, in turn
	 * @return the response
	 */
	private static JsonNode matrix(double... points) {
		ObjectNode response = JSON.createObjectNode().put("status", "success");
		ObjectNode data = response.putObject("data").put("resultType", "matrix");
		ArrayNode result = data.putArray("result");
		if (points.length > 0) {
			ObjectNode series = result.addObject();
			series.putObject("metric");
			ArrayNode values = series.putArray("values");
			for (int i = 0; i < points.length; i += 2) {
				values.addArray().add(points[i]).add(String.valueOf(points[i + 1]));
			}
		}
		return response;
	}

}
