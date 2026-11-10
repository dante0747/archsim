package io.archsim.engine.calibration.prometheus;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.calibration.mapping.TimeRange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Recorded responses and the matrix checks, as the Python engine's
 * {@code RecordedPrometheus} and {@code _matrix} read them.
 */
class RecordedPrometheusTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void of_recording_hasItsSourceAndRange() {
		RecordedPrometheus recorded = RecordedPrometheus.of(JSON.readTree("""
				{"source": "fixture", "range": {"start": "a", "end": "b", "step": 5}, "responses": {}}"""), "f.json");

		assertThat(recorded.source()).isEqualTo("fixture");
		assertThat(recorded.recordedRange()).hasValue(new TimeRange("a", "b", 5));
	}

	@Test
	void of_recordingWithoutASource_isNamedByItsFile() {
		RecordedPrometheus recorded = RecordedPrometheus.of(JSON.readTree("{}"), "f.json");

		assertThat(recorded.source()).isEqualTo("recorded responses f.json");
		assertThat(recorded.recordedRange()).isEmpty();
	}

	@Test
	void of_responsesThatAreNotAnObject_areRefused() {
		assertThatExceptionOfType(PrometheusException.class)
			.isThrownBy(() -> RecordedPrometheus.of(JSON.readTree("{\"responses\": [1]}"), ""))
			.withMessage("a recording's 'responses' must map each query to its response");
	}

	@ParameterizedTest
	@ValueSource(strings = { "[1, 2]", "null", "\"responses\"", "3" })
	void of_aRecordingThatIsNotAnObject_isRefusedWherePythonFailedToReadIt(String recording) {
		// Python raised AttributeError: 'list' object has no attribute 'get'
		assertThatExceptionOfType(PrometheusException.class)
			.isThrownBy(() -> RecordedPrometheus.of(JSON.readTree(recording), ""))
			.withMessage("a recording must be a JSON object, with its 'responses'");
	}

	@Test
	void queryRange_queryNotRecorded_isRefused() {
		RecordedPrometheus recorded = RecordedPrometheus.of(JSON.readTree("{}"), "");

		assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> recorded.queryRange("up", 0, 1, 1))
			.withMessage("no recorded response for query: up");
	}

	@Test
	void matrix_failureWithAKnownErrorType_namesItAndKeepsWhatTheServerSaidAsDetail() {
		PrometheusException ex = catchThrowableOfType(PrometheusException.class, () -> Matrices
			.of(JSON.readTree("{\"status\": \"error\", \"errorType\": \"timeout\", \"error\": \"boom\"}"), "up"));

		assertThat(ex).hasMessage("query failed (timeout): up");
		assertThat(ex.detail()).hasValue("boom");
	}

	@ParameterizedTest
	@MethodSource("unusableResponses")
	void matrix_responseItCannotUse_isRefusedInThePythonEnginesWords(String response, String message) {
		assertThatExceptionOfType(PrometheusException.class)
			.isThrownBy(() -> Matrices.of(JSON.readTree(response), "up"))
			.withMessage(message);
	}

	static Stream<Arguments> unusableResponses() {
		return Stream.of(Arguments.of("[]", "not a Prometheus API response for: up"),
				Arguments.of("{\"status\": \"error\", \"errorType\": \"made-up\"}", "query failed (error): up"),
				Arguments.of("{\"status\": \"success\", \"data\": {\"resultType\": \"vector\"}}",
						"expected a range (matrix) result for: up"),
				Arguments.of("{\"status\": \"success\", \"data\": {\"resultType\": \"matrix\", \"result\": [1]}}",
						"a malformed matrix result for: up"));
	}

	@Test
	void matrix_noResult_isNoSeries() {
		assertThat(
				Matrices.of(JSON.readTree("{\"status\": \"success\", \"data\": {\"resultType\": \"matrix\"}}"), "up"))
			.isEmpty();
	}

}
