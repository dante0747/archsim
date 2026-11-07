package io.archsim.engine.calibration.prometheus;

import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.mapping.TimeRange;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.text.PythonText;

/**
 * Prometheus responses recorded earlier, {@code {source, range: {start, end, step},
 * responses: {query: response}}}, as the Python engine's {@code RecordedPrometheus} reads
 * them. The range is the window the queries covered; calibration uses it.
 */
public final class RecordedPrometheus implements PrometheusClient {

	/** The longest source name kept. */
	private static final int SOURCE_LENGTH = 200;

	private final ObjectNode responses;

	private final String source;

	private final @Nullable TimeRange range;

	private RecordedPrometheus(ObjectNode responses, String source, @Nullable TimeRange range) {
		this.responses = responses;
		this.source = source;
		this.range = range;
	}

	/**
	 * A recording.
	 * @param data the recording
	 * @param name the file it came from, for its source when it names none; empty for
	 * none
	 * @return the recorded responses
	 * @throws PrometheusException when its responses are not an object of queries
	 * @throws io.archsim.model.codec.BindingException when its range is not one
	 */
	public static RecordedPrometheus of(JsonNode data, String name) {
		JsonNode window = data.path("range");
		JsonNode responses = data.path("responses");
		if (PythonText.truthy(responses) && !responses.isObject()) {
			throw new PrometheusException("a recording's 'responses' must map each query to its response");
		}
		JsonNode named = data.path("source");
		String source = PythonText.truthy(named) ? PythonText.str(named) : ("recorded responses " + name).strip();
		return new RecordedPrometheus(
				responses.isObject() ? (ObjectNode) responses.deepCopy() : JsonNodeFactory.instance.objectNode(),
				firstCodePoints(source), PythonText.truthy(window) ? RecordCodec.read(window, TimeRange.class) : null);
	}

	@Override
	public String source() {
		return this.source;
	}

	@Override
	public JsonNode queryRange(String query, double start, double end, double step) {
		JsonNode response = this.responses.get(query);
		if (response == null) {
			throw new PrometheusException("no recorded response for query: " + query);
		}
		return Matrices.of(response, query);
	}

	@Override
	public Optional<TimeRange> recordedRange() {
		return Optional.ofNullable(this.range);
	}

	private static String firstCodePoints(String text) {
		return (text.codePointCount(0, text.length()) <= SOURCE_LENGTH) ? text
				: text.substring(0, text.offsetByCodePoints(0, SOURCE_LENGTH));
	}

}
