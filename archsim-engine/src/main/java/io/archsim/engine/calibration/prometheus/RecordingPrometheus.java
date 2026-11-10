package io.archsim.engine.calibration.prometheus;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A client that keeps every response another one gives, as the Python engine's
 * {@code RecordingPrometheus}: a recording of a calibration's queries, which
 * {@link RecordedPrometheus} reads back for offline replays and tests. It answers for the
 * range it is asked, whatever the client it wraps was recorded over.
 */
public final class RecordingPrometheus implements PrometheusClient {

	/** A time on the second, as {@code datetime.isoformat()} writes it. */
	private static final DateTimeFormatter WHOLE_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'")
		.withZone(ZoneOffset.UTC);

	/**
	 * A time with a fraction of a second, as {@code datetime.isoformat()} writes it: to
	 * the microsecond.
	 */
	private static final DateTimeFormatter MICROSECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
		.withZone(ZoneOffset.UTC);

	/** The microseconds in a second. */
	private static final int MICROS_PER_SECOND = 1_000_000;

	/** The nanoseconds in a microsecond. */
	private static final int NANOS_PER_MICRO = 1_000;

	private final PrometheusClient client;

	private final ObjectNode responses = JsonNodeFactory.instance.objectNode();

	private @Nullable Window window;

	private RecordingPrometheus(PrometheusClient client) {
		this.client = client;
	}

	/**
	 * A recording of what a client answers.
	 * @param client the client whose answers are kept
	 * @return the recording client
	 */
	public static RecordingPrometheus of(PrometheusClient client) {
		return new RecordingPrometheus(client);
	}

	@Override
	public String source() {
		return this.client.source();
	}

	/**
	 * A range query's series, from the client this one wraps, kept as a matrix response
	 * with the range it covered.
	 */
	@Override
	public JsonNode queryRange(String query, double start, double end, double step) {
		JsonNode series = this.client.queryRange(query, start, end, step);
		this.window = new Window(start, end, step);
		ObjectNode response = this.responses.putObject(query).put("status", "success");
		response.putObject("data").put("resultType", "matrix").set("result", series.deepCopy());
		return series;
	}

	/**
	 * What was recorded, as the Python engine saved it: the source, the range the last
	 * query covered if there was one, and each query's response.
	 * @return {@code {source, range, responses}}, a copy
	 */
	public ObjectNode recording() {
		ObjectNode recording = JsonNodeFactory.instance.objectNode().put("source", source());
		if (this.window != null) {
			recording.putObject("range")
				.put("start", isoFormat(this.window.start()))
				.put("end", isoFormat(this.window.end()))
				.put("step", this.window.step());
		}
		recording.set("responses", this.responses.deepCopy());
		return recording;
	}

	/**
	 * A time as Python's {@code datetime.fromtimestamp(t, UTC).isoformat()} writes it,
	 * with {@code Z} for {@code +00:00}: its whole seconds, and its fraction to the
	 * microsecond, rounded half to even, as {@code datetime} rounds it.
	 * @param timestamp seconds since the epoch
	 * @return the time
	 */
	private static String isoFormat(double timestamp) {
		long seconds = (long) timestamp;
		long micros = (long) Math.rint((timestamp - seconds) * MICROS_PER_SECOND);
		if (micros >= MICROS_PER_SECOND) {
			seconds++;
			micros -= MICROS_PER_SECOND;
		}
		else if (micros < 0) {
			seconds--;
			micros += MICROS_PER_SECOND;
		}
		Instant instant = Instant.ofEpochSecond(seconds, micros * NANOS_PER_MICRO);
		return ((micros == 0) ? WHOLE_SECONDS : MICROSECONDS).format(instant);
	}

	/**
	 * The range a query covered.
	 *
	 * @param start its start, in seconds since the epoch
	 * @param end its end
	 * @param step the seconds between points
	 */
	private record Window(double start, double end, double step) {

	}

}
