package io.archsim.engine.calibration.prometheus;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.calibration.mapping.TimeRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client that keeps every response it is given, as the Python engine's
 * {@code RecordingPrometheus}, for a recording {@link RecordedPrometheus} reads back.
 */
class RecordingPrometheusTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** 2025-10-01T00:00:00Z. */
	private static final double START = 1_759_276_800.0;

	private static final JsonNode SERIES = JSON
		.readTree("[{\"metric\": {\"job\": \"api\"}, \"values\": [[1759276800, \"1.5\"]]}]");

	@Test
	void recording_aQueryAnswered_holdsItsResponseAndRange() {
		RecordingPrometheus recording = RecordingPrometheus.of(new Answering(SERIES));

		recording.queryRange("up", START, START + 120, 5.0);

		assertThat(recording.recording()).isEqualTo(JSON.readTree("""
				{"source": "a server",
				 "range": {"start": "2025-10-01T00:00:00Z", "end": "2025-10-01T00:02:00Z", "step": 5.0},
				 "responses": {"up": {"status": "success", "data": {"resultType": "matrix", "result": %s}}}}"""
			.formatted(SERIES)));
	}

	@Test
	void recording_nothingAsked_holdsTheSourceAlone() {
		RecordingPrometheus recording = RecordingPrometheus.of(new Answering(SERIES));

		JsonNode written = recording.recording();

		assertThat(written).isEqualTo(JSON.readTree("{\"source\": \"a server\", \"responses\": {}}"));
	}

	@Test
	void recording_aFractionOfASecond_isWrittenInMicroseconds() {
		RecordingPrometheus recording = RecordingPrometheus.of(new Answering(SERIES));

		recording.queryRange("up", START + 0.5, START + 0.1234567, 5.0);

		assertThat(recording.recording().at("/range"))
			.isEqualTo(JSON.readTree("{\"start\": \"2025-10-01T00:00:00.500000Z\","
					+ " \"end\": \"2025-10-01T00:00:00.123457Z\", \"step\": 5.0}"));
	}

	@Test
	void queryRange_anyQuery_answersWhatTheClientItWrapsAnswers() {
		RecordingPrometheus recording = RecordingPrometheus.of(new Answering(SERIES));

		JsonNode answer = recording.queryRange("up", START, START + 60, 5.0);

		assertThat(answer).isEqualTo(SERIES);
	}

	@Test
	void recordedRange_aRecordingRecordedAgain_isNone() {
		RecordingPrometheus recording = RecordingPrometheus.of(RecordedPrometheus
			.of(JSON.readTree("{\"range\": {\"start\": \"a\", \"end\": \"b\"}, \"responses\": {}}"), "recorded.json"));

		Optional<TimeRange> range = recording.recordedRange();

		assertThat(range).isEmpty();
	}

	/**
	 * A server that answers every query with the same series.
	 *
	 * @param series the series
	 */
	private record Answering(JsonNode series) implements PrometheusClient {

		@Override
		public String source() {
			return "a server";
		}

		@Override
		public JsonNode queryRange(String query, double start, double end, double step) {
			return this.series;
		}

	}

}
