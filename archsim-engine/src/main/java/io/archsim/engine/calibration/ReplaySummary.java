package io.archsim.engine.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * One line for people on how well a calibrated model reproduces what was observed: the
 * end-to-end p95 and always the worst of all replay checks, since a good p95 can hide a
 * bad p99 or part.
 */
public final class ReplaySummary {

	/** How the line names a check's metric; latency quantiles keep their names. */
	private static final Map<String, String> METRIC_WORDS = Map.of("throughputRps", "throughput", "cpuCores", "CPU",
			"memoryMiB", "memory");

	private static final double PERCENT = 100;

	private ReplaySummary() {
	}

	/**
	 * The line for a calibration record.
	 * @param record the record a calibration wrote into the design; {@code null} for none
	 * @return the line; empty without replay checks that measured an error
	 */
	public static Optional<String> of(@Nullable JsonNode record) {
		if (record == null || !PythonText.truthy(record)) {
			return Optional.empty();
		}
		List<JsonNode> checks = measured(record.path("replay"));
		if (checks.isEmpty()) {
			return Optional.empty();
		}
		JsonNode range = record.path("timeRange");
		String observed = PythonText.truthy(range) ? PythonText.str(range) : "the observed range";
		String worst = worst(checks);
		return Optional
			.of(endToEndP95(checks)
				.map((error) -> "This model reproduces the observed p95 (" + observed + ") within "
						+ PythonFormat.format(Math.abs(error) * PERCENT, ".0f") + "% ("
						+ ((error > 0) ? "over" : "under") + "); " + worst + ".")
				.orElseGet(() -> "Replay of " + observed + ": " + worst + "."));
	}

	/**
	 * The replay checks that measured an error.
	 * @param replay the record's replay checks
	 * @return the checks whose error is not {@code null}, in order
	 */
	private static List<JsonNode> measured(JsonNode replay) {
		List<JsonNode> checks = new ArrayList<>();
		if (PythonText.truthy(replay)) {
			replay.forEach((check) -> {
				if (!check.path("error").isNull() && !check.path("error").isMissingNode()) {
					checks.add(check);
				}
			});
		}
		return checks;
	}

	/**
	 * The largest error of all checks, in words.
	 * @param checks the checks that measured an error
	 * @return how many there are and the largest, the first of equal ones
	 */
	private static String worst(List<JsonNode> checks) {
		JsonNode worst = checks.getFirst();
		for (JsonNode check : checks) {
			if (Math.abs(check.path("error").doubleValue()) > Math.abs(worst.path("error").doubleValue())) {
				worst = check;
			}
		}
		String metric = PythonText.str(worst.path("metric"));
		return "the largest of " + checks.size() + " replay errors is "
				+ PythonFormat.format(worst.path("error").doubleValue(), "+.0%") + " ("
				+ PythonText.str(worst.path("label")) + " " + METRIC_WORDS.getOrDefault(metric, metric) + ")";
	}

	/**
	 * The error of the end-to-end p95.
	 * @param checks the checks that measured an error
	 * @return the first end-to-end p95 check's error; empty when there is none
	 */
	private static Optional<Double> endToEndP95(List<JsonNode> checks) {
		return checks.stream()
			.filter((check) -> "end_to_end".equals(check.path("scope").asString(""))
					&& "p95".equals(check.path("metric").asString("")))
			.findFirst()
			.map((check) -> check.path("error").doubleValue());
	}

}
