package io.archsim.benchmarks.range;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.inputs.ModelDistributions;
import io.archsim.benchmarks.values.PythonValues;

/**
 * The model inputs whose run-to-run variation the range carries, and how one is moved:
 * each is scaled as a whole, a distribution keeping its shape.
 */
final class RangeInputs {

	/**
	 * The inputs calibration measures afresh each run, whose variation the range carries.
	 */
	static final List<String> VARIED = List.of("serviceTimeMs", "apiServiceTimeMs", "clientLinkLatencyMs",
			"apiLinkLatencyMs", "readServiceTimeMs", "writeServiceTimeMs", "commitFlushMs", "dbCpuShare",
			"dbLinkLatencyMs", "cacheServiceTimeMs", "cacheLinkLatencyMs", "publishTimeMs", "consumerTimeMs",
			"fetchOverheadMs");

	/**
	 * The inputs the range leaves as calibrated: records, settings of the real stack,
	 * relative curves, memory (it sets no knee), GC pauses, which the kept runs take from
	 * one trial and which move no knee (W21), and the host machine (W27: the machine's,
	 * not a calibration's). Every input is in one of the two lists.
	 */
	static final List<String> FIXED = List.of("measuredLowLoad", "anchor", "serviceTimeByLoad", "gcPauseMs",
			"gcEveryRequests", "consumerConcurrency", "serviceMemoryBaselineMb", "serviceMemoryPerRpsMb",
			"serviceMemoryPerRequestMb", "apiMemoryBaselineMb", "apiMemoryPerRpsMb", "apiMemoryPerRequestMb", "host");

	/** A distribution's numbers that are times, which scaling moves. */
	private static final List<String> TIMES = List.of("value", "mean", "median", "p95", "min", "max");

	/** The CPU share's input, a share of the query time: never above 1. */
	private static final String CPU_SHARE = "dbCpuShare";

	private RangeInputs() {
	}

	/**
	 * The varied inputs of a design that have a scale.
	 * @param inputs the inputs, each with its value
	 * @return their names, in {@link #VARIED}'s order
	 */
	static List<String> keys(JsonNode inputs) {
		return VARIED.stream().filter((key) -> {
			JsonNode input = inputs.path(key);
			Double scale = input.isObject() ? scale(PythonValues.get(input, "value")) : null;
			return scale != null && scale > 0;
		}).toList();
	}

	/**
	 * The number an input's variation is measured on: itself, or a distribution's mean as
	 * the engine samples it (for the service's CPU per request, the CPU itself).
	 * @param value the input's value
	 * @return the number; {@code null} for a value that has none
	 */
	static @Nullable Double scale(JsonNode value) {
		if (value.isNumber()) {
			return value.doubleValue();
		}
		return (value.isObject() && value.has("kind")) ? ModelDistributions.mean(value) : null;
	}

	/**
	 * An input's value times a factor: a number, or every time of a distribution, its
	 * shape kept.
	 * @param value the value
	 * @param factor the factor
	 * @return the scaled value; one with nothing to scale as it is
	 */
	static JsonNode scaled(JsonNode value, double factor) {
		JsonNode times = PythonValues.number(factor);
		if (value.isNumber()) {
			return PythonValues.multiply(value, times);
		}
		if (!value.isObject()) {
			return value;
		}
		ObjectNode out = ((ObjectNode) value).deepCopy();
		TIMES.stream()
			.filter((key) -> out.path(key).isNumber())
			.forEach((key) -> out.set(key, PythonValues.multiply(out.get(key), times)));
		if ("empirical".equals(out.path("kind").stringValue(""))) {
			ArrayNode quantiles = out.putArray("quantiles");
			value.get("quantiles")
				.forEach((quantile) -> quantiles.addArray()
					.add(quantile.get(0))
					.add(PythonValues.multiply(quantile.get(1), times)));
		}
		return out;
	}

	/**
	 * A copy of the inputs with one of them times a factor; the CPU share at most 1.
	 * @param inputs the inputs
	 * @param key the input to move
	 * @param factor the factor
	 * @return the copy
	 */
	static ObjectNode perturbed(JsonNode inputs, String key, double factor) {
		ObjectNode out = ((ObjectNode) inputs).deepCopy();
		JsonNode value = scaled(inputs.get(key).get("value"), factor);
		if (key.equals(CPU_SHARE)) {
			value = PythonValues.min(List.of(value, PythonValues.number(1.0)));
		}
		((ObjectNode) out.get(key)).set("value", value);
		return out;
	}

}
