package io.archsim.engine.reporting;

import java.util.List;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.metrics.LatencySummary;
import io.archsim.engine.numeric.PythonRound;

/**
 * How a report writes its figures, as the Python engine wrote them: counts as integers,
 * rates and times rounded to 4 decimals with Python's {@code round}, and {@code null} for
 * a figure that is missing, not a number or infinite. The report's digests hash this
 * text, so an integer must not become a float.
 */
final class Figures {

	/** The decimals a report keeps. */
	static final int DIGITS = 4;

	/**
	 * Milliseconds in a second: simulated time is in milliseconds, a report's in seconds.
	 */
	static final double MS_PER_SECOND = 1000.0;

	static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private Figures() {
	}

	/**
	 * A figure rounded to 4 decimals.
	 * @param value the figure
	 * @return the rounded figure, or a JSON {@code null}
	 */
	static JsonNode rounded(double value) {
		return rounded(value, DIGITS);
	}

	/**
	 * A figure rounded.
	 * @param value the figure
	 * @param digits how many decimals
	 * @return the rounded figure, or a JSON {@code null} when not finite
	 */
	static JsonNode rounded(double value, int digits) {
		return Double.isFinite(value) ? NODES.numberNode(PythonRound.round(value, digits)) : NODES.nullNode();
	}

	/**
	 * A figure rounded to 4 decimals, if there is one.
	 * @param value the figure
	 * @return the rounded figure, or a JSON {@code null}
	 */
	static JsonNode rounded(OptionalDouble value) {
		return value.isPresent() ? rounded(value.getAsDouble()) : NODES.nullNode();
	}

	/**
	 * A ratio rounded to 4 decimals, unless its denominator is zero.
	 * @param numerator the numerator
	 * @param denominator the denominator
	 * @return the rounded ratio, or a JSON {@code null}
	 */
	static JsonNode ratio(double numerator, double denominator) {
		return (denominator != 0) ? rounded(numerator / denominator) : NODES.nullNode();
	}

	/**
	 * A ratio of two counts rounded to 4 decimals, unless the denominator is zero.
	 * @param numerator the numerator
	 * @param denominator the denominator
	 * @return the rounded ratio, or a JSON {@code null}
	 */
	static JsonNode ratio(long numerator, long denominator) {
		return ratio((double) numerator, (double) denominator);
	}

	/**
	 * A figure as it is: a JSON {@code null} for none.
	 * @param value the figure
	 * @return the figure's JSON
	 */
	static JsonNode nullable(@Nullable Double value) {
		return (value != null) ? NODES.numberNode(value) : NODES.nullNode();
	}

	/**
	 * A series of figures rounded to 4 decimals, cut to the buckets the run reached.
	 * @param values the figures, one per bucket; not a number where there is none
	 * @param used how many buckets to keep
	 * @return the series
	 */
	static ArrayNode roundedSeries(double[] values, int used) {
		ArrayNode series = NODES.arrayNode();
		for (int bucket = 0; bucket < used; bucket++) {
			series.add(rounded(values[bucket]));
		}
		return series;
	}

	/**
	 * A series of figures, rounded and cut to the buckets the run reached.
	 * @param values the figures, one per bucket; {@code null} where there is none
	 * @param used how many buckets to keep
	 * @return the series
	 */
	static ArrayNode roundedSeries(List<@Nullable Double> values, int used) {
		ArrayNode series = NODES.arrayNode();
		for (int bucket = 0; bucket < used; bucket++) {
			Double value = values.get(bucket);
			series.add((value != null) ? rounded(value) : NODES.nullNode());
		}
		return series;
	}

	/**
	 * A series of counts, cut to the buckets the run reached.
	 * @param values the counts, one per bucket
	 * @param used how many buckets to keep
	 * @return the series
	 */
	static ArrayNode countSeries(long[] values, int used) {
		ArrayNode series = NODES.arrayNode();
		for (int bucket = 0; bucket < used; bucket++) {
			series.add(values[bucket]);
		}
		return series;
	}

	/**
	 * A series of figures already rounded, cut to the buckets the run reached.
	 * @param values the figures, {@code null} where there is none
	 * @param used how many buckets to keep
	 * @return the series
	 */
	static ArrayNode series(List<@Nullable Double> values, int used) {
		ArrayNode series = NODES.arrayNode();
		for (int bucket = 0; bucket < used; bucket++) {
			series.add(nullable(values.get(bucket)));
		}
		return series;
	}

	/**
	 * A latency distribution's summary.
	 * @param summary the summary
	 * @return {@code count}, {@code mean}, {@code p50}, {@code p90}, {@code p95},
	 * {@code p99} and {@code max}
	 */
	static ObjectNode latency(LatencySummary summary) {
		ObjectNode latency = NODES.objectNode();
		latency.put("count", summary.count());
		latency.set("mean", nullable(summary.mean()));
		latency.set("p50", nullable(summary.p50()));
		latency.set("p90", nullable(summary.p90()));
		latency.set("p95", nullable(summary.p95()));
		latency.set("p99", nullable(summary.p99()));
		latency.set("max", nullable(summary.max()));
		return latency;
	}

}
