package io.archsim.engine.metrics;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The frozen engine's {@code metrics} module on recorded operations, read from
 * {@code metrics/cases.json} (recorded by {@code scripts/export_metrics_cases.py}, since
 * deleted), and how the tests read its values: floats as Python's hex, a missing figure
 * as not a number.
 */
final class MetricsCases {

	/** The recorded cases. */
	static final JsonNode CASES = read();

	/**
	 * How far a percentile, before it is rounded, may be from Python's: it goes through
	 * {@code log} and {@code pow}, whose last place {@code StrictMath} and Python's C
	 * library may round apart.
	 */
	static final Comparator<Double> LIBM = MetricsCases::closeToLibm;

	private static final double LIBM_RELATIVE = 1e-14;

	private MetricsCases() {
	}

	/**
	 * One dynamic test per recorded timeline.
	 * @param check what to check on the run and its timeline
	 * @return the tests
	 */
	static Stream<DynamicTest> timelines(RunCheck check) {
		return list(CASES.get("timelines")).stream()
			.map((run) -> DynamicTest.dynamicTest("run to " + hex(run.get("timeline").get("endMs")) + " ms",
					() -> check.check(run, timeline(run.get("timeline")))));
	}

	/**
	 * A recorded latency summary.
	 * @param expected the summary as Python wrote it
	 * @return the summary
	 */
	static LatencySummary summary(JsonNode expected) {
		return new LatencySummary(expected.get("count").asLong(), optionalHex(expected.get("mean")),
				optionalHex(expected.get("p50")), optionalHex(expected.get("p90")), optionalHex(expected.get("p95")),
				optionalHex(expected.get("p99")), optionalHex(expected.get("max")));
	}

	static List<JsonNode> list(JsonNode array) {
		List<JsonNode> items = new ArrayList<>();
		array.forEach(items::add);
		return items;
	}

	static double hex(JsonNode value) {
		return Double.parseDouble(value.stringValue());
	}

	static List<Double> hexes(JsonNode values) {
		return list(values).stream().map(MetricsCases::hex).toList();
	}

	static double nanHex(JsonNode value) {
		return value.isNull() ? Double.NaN : hex(value);
	}

	static List<Double> nanHexes(JsonNode values) {
		return list(values).stream().map(MetricsCases::nanHex).toList();
	}

	static double orNaN(@Nullable Double value) {
		return (value != null) ? value : Double.NaN;
	}

	static List<Double> nan(List<@Nullable Double> values) {
		return values.stream().map(MetricsCases::orNaN).toList();
	}

	static List<Long> longs(JsonNode values) {
		return list(values).stream().map(JsonNode::asLong).toList();
	}

	private static @Nullable Double optionalHex(JsonNode value) {
		return value.isNull() ? null : hex(value);
	}

	private static Timeline timeline(JsonNode cut) {
		return new Timeline(hex(cut.get("endMs")), hex(cut.get("windowStart")), hex(cut.get("windowEnd")));
	}

	private static int closeToLibm(double actual, double expected) {
		boolean close = Math.abs(actual - expected) <= Math.abs(expected) * LIBM_RELATIVE;
		return (close || Double.compare(actual, expected) == 0) ? 0 : Double.compare(actual, expected);
	}

	private static JsonNode read() {
		try (InputStream cases = Objects.requireNonNull(MetricsCases.class.getResourceAsStream("cases.json"),
				"cases.json")) {
			return JsonMapper.builder().build().readTree(cases);
		}
		catch (IOException ex) {
			throw new IllegalStateException("cannot read metrics/cases.json", ex);
		}
	}

	/**
	 * Checks one recorded run's primitives on its timeline.
	 */
	@FunctionalInterface
	interface RunCheck {

		/**
		 * Checks a run.
		 * @param run the recorded run
		 * @param timeline its timeline
		 */
		void check(JsonNode run, Timeline timeline);

	}

}
