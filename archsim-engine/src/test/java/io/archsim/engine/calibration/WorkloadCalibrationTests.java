package io.archsim.engine.calibration;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.prometheus.PrometheusSeries.Point;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The observed rate as a {@code trace} pattern, as the Python engine's {@code _trace}
 * writes it. Expected values are Python's.
 */
class WorkloadCalibrationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void trace_rangeAsLongAsTheRun_isReplayedInRealTime() {
		ObjectNode trace = WorkloadCalibration.trace(List.of(new Point(0, 10), new Point(30, 20), new Point(60, 30)), 0,
				60, workload("{\"durationS\": 60}"));

		assertThat(trace).isEqualTo(JSON.readTree("""
				{"kind": "trace", "points": [[0.0, 10.0], [30.0, 20.0], [60.0, 30.0]],
				 "source": "observed rate over 60 s replayed in real time in the 60 s run"}"""));
	}

	@Test
	void trace_rangeShorterThanTheRun_isStretchedAndNegativeRatesAreZero() {
		ObjectNode trace = WorkloadCalibration.trace(List.of(new Point(0, 10), new Point(10, -5)), 0, 10,
				workload("{\"durationS\": 40}"));

		assertThat(trace).isEqualTo(JSON.readTree("""
				{"kind": "trace", "points": [[0.0, 10.0], [40.0, 0.0]],
				 "source": "observed rate over 10 s stretched over the 40 s run (4.0x slower)"}"""));
	}

	@Test
	void trace_onePointAndNoDuration_isCompressedIntoAMinuteAndHeldToItsEnd() {
		ObjectNode trace = WorkloadCalibration.trace(List.of(new Point(0, 10)), 0, 120, workload("{}"));

		assertThat(trace).isEqualTo(JSON.readTree("""
				{"kind": "trace", "points": [[0.0, 10.0], [60.0, 10.0]],
				 "source": "observed rate over 2 min compressed into the 60 s run (2.0x faster)"}"""));
	}

	@ParameterizedTest
	@CsvSource({ "59.4, 59 s", "120, 2 min", "7199, 120 min", "7200, 2.0 h", "9000, 2.5 h" })
	void spanText_span_isInSecondsMinutesOrHours(double seconds, String text) {
		assertThat(WorkloadCalibration.spanText(seconds)).isEqualTo(text);
	}

	private static ObjectNode workload(String json) {
		return (ObjectNode) JSON.readTree(json);
	}

}
