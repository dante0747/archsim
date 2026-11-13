package io.archsim.benchmarks.parity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.results.KneePairs;
import io.archsim.benchmarks.values.PythonValues;

/**
 * The parity page's tables: the cases that miss the bar, the kept calibrations' knees and
 * each case.
 */
final class ParityTables {

	private ParityTables() {
	}

	/**
	 * The cases that miss the bar: each metric they missed by its numbers, and the runs
	 * that failed otherwise than Python's in their own words.
	 * @param results {@code simulation-parity.json}'s results
	 * @return the section's lines
	 */
	static List<String> misses(JsonNode results) {
		List<JsonNode> missing = results.valueStream()
			.filter((result) -> !result.get("meetsTheBar").booleanValue())
			.toList();
		if (missing.isEmpty()) {
			return List.of("None: every metric of every case is inside Python's range and within 2% of its median, "
					+ "and no seed failed otherwise than Python's.");
		}
		List<String> rows = missing.stream().flatMap((result) -> missedMetrics(result).stream()).toList();
		List<String> failures = missing.stream().flatMap((result) -> otherFailures(result).stream()).toList();
		List<String> lines = new ArrayList<>();
		if (!rows.isEmpty()) {
			lines
				.add("| Template | Case | Metric | Python's range | Python's median | Java's median | Inside the range "
						+ "| Within 2% |");
			lines.add("| --- | --- | --- | --- | --- | --- | --- | --- |");
			lines.addAll(rows);
		}
		if (!failures.isEmpty()) {
			if (!lines.isEmpty()) {
				lines.add("");
			}
			lines.addAll(List.of("Runs that failed otherwise than Python's:", ""));
			lines.addAll(failures);
		}
		return lines;
	}

	// a row per metric a case missed, by its numbers
	private static List<String> missedMetrics(JsonNode result) {
		Set<String> missed = strings(result.get("missed"));
		return result.get("metrics")
			.valueStream()
			.filter((metric) -> missed.contains(metric.get("metric").stringValue()))
			.map((metric) -> "| " + caseName(result) + " | " + metricCells(metric) + " |")
			.toList();
	}

	// the misses that name no metric: runs that failed otherwise than Python's
	private static List<String> otherFailures(JsonNode result) {
		Set<String> metrics = result.get("metrics")
			.valueStream()
			.map((metric) -> metric.get("metric").stringValue())
			.collect(Collectors.toSet());
		return result.get("missed")
			.valueStream()
			.map(JsonNode::stringValue)
			.filter((miss) -> !metrics.contains(miss))
			.map((miss) -> "- " + result.get("template").stringValue() + " `" + result.get("scenario").stringValue()
					+ "`: " + miss)
			.toList();
	}

	/**
	 * How many rows hold true under a key.
	 * @param rows the rows
	 * @param key the key
	 * @return the count
	 */
	static long count(List<ObjectNode> rows, String key) {
		return rows.stream().filter((row) -> row.get(key).booleanValue()).count();
	}

	/**
	 * The kept calibrations' knees on both engines.
	 * @param rows the pairs ({@link KneePairs})
	 * @return the section's lines
	 */
	static List<String> knees(List<ObjectNode> rows) {
		if (rows.isEmpty()) {
			return List.of("None re-simulated.");
		}
		long within = count(rows, "withinTwoPercent");
		long same = rows.stream().filter(KneePairs::sameKnee).count();
		long otherInputs = rows.stream()
			.filter((row) -> row.get("sameInputs").isBoolean() && !row.get("sameInputs").booleanValue())
			.count();
		List<String> lines = new ArrayList<>(List.of(
				"**" + within + " of " + rows.size()
						+ "** kept calibrations' re-simulated knees are within 2% of the frozen engine's, " + same
						+ " the same; " + count(rows, "checked") + " of " + rows.size()
						+ " checked on the frozen engine, " + count(rows, "resimulated") + " of " + rows.size()
						+ " on Java, " + otherInputs + " with other derived inputs than Python's.",
				"",
				"| Experiment | Calibration | Python's knee (req/s) | Java's knee (req/s) | Same inputs | Within 2% |",
				"| --- | --- | --- | --- | --- | --- |"));
		rows.forEach((row) -> lines.add(kneeRow(row)));
		return lines;
	}

	private static String kneeRow(JsonNode row) {
		JsonNode sameInputs = row.get("sameInputs");
		return "| " + row.get("experiment").stringValue() + " | " + row.get("calibration").stringValue() + " | "
				+ (row.get("checked").booleanValue() ? ParityNumbers.number(row.get("python"))
						: "no frozen-engine check")
				+ " | "
				+ (row.get("resimulated").booleanValue() ? ParityNumbers.number(row.get("java")) : "not re-simulated")
				+ " | " + (PythonValues.isNone(sameInputs) ? "" : ParityNumbers.yes(sameInputs.booleanValue())) + " | "
				+ ParityNumbers.yes(row.get("withinTwoPercent").booleanValue()) + " |";
	}

	/**
	 * Each case: its runs, its exact replays, whether it meets the bar, and its metric
	 * furthest from Python's.
	 * @param results {@code simulation-parity.json}'s results
	 * @return the section's lines
	 */
	static List<String> cases(JsonNode results) {
		List<String> lines = new ArrayList<>(List.of(
				"| Template | Case | Runs | Exact replays | Meets the bar | Furthest metric "
						+ "| Python's median | Java's median | Off by |",
				"| --- | --- | --- | --- | --- | --- | --- | --- | --- |"));
		results.forEach((result) -> lines.add("| " + caseName(result) + " | " + result.get("runs").asString() + " | "
				+ result.get("exact").asString() + " | " + ParityNumbers.yes(result.get("meetsTheBar").booleanValue())
				+ " | " + furthest(result.get("metrics")) + " |"));
		return lines;
	}

	// a case's metric furthest from Python's, as the last four cells of its row
	private static String furthest(JsonNode metrics) {
		if (metrics.isEmpty()) {
			return "none measured | | |";
		}
		JsonNode metric = metrics.get(0);
		for (JsonNode candidate : metrics) {
			if (distance(candidate) > distance(metric)) {
				metric = candidate;
			}
		}
		if (distance(metric) == 0) {
			return "none off | | |";
		}
		return metric.get("metric").stringValue() + " | " + ParityNumbers.number(metric.get("pythonMedian")) + " | "
				+ javaMedian(metric) + " | " + ParityNumbers.percent(metric.get("distance"));
	}

	private static String metricCells(JsonNode metric) {
		boolean withinTwoPercent = metric.get("withinTwoPercent").booleanValue();
		String within = ParityNumbers.yes(withinTwoPercent);
		if (!withinTwoPercent && !PythonValues.isNone(metric.get("distance"))) {
			within += " (" + ParityNumbers.percent(metric.get("distance")) + ")";
		}
		return metric.get("metric").stringValue() + " | " + pythonRange(metric) + " | "
				+ ParityNumbers.number(metric.get("pythonMedian")) + " | " + javaMedian(metric) + " | "
				+ ParityNumbers.yes(metric.get("insideRange").booleanValue()) + " | " + within;
	}

	/**
	 * Java's median, or that no Java run measured it.
	 * @param metric a case's metric
	 * @return its text
	 */
	static String javaMedian(JsonNode metric) {
		JsonNode median = metric.get("javaMedian");
		return PythonValues.isNone(median) ? ParityNumbers.NOT_MEASURED : ParityNumbers.number(median);
	}

	/**
	 * Python's seed range: {@code 18 to 26}.
	 * @param metric a case's metric
	 * @return its text
	 */
	static String pythonRange(JsonNode metric) {
		return ParityNumbers.number(metric.get("pythonLow")) + " to " + ParityNumbers.number(metric.get("pythonHigh"));
	}

	private static double distance(JsonNode metric) {
		JsonNode distance = metric.get("distance");
		return PythonValues.isNone(distance) ? Double.POSITIVE_INFINITY : distance.doubleValue();
	}

	private static String caseName(JsonNode result) {
		return result.get("template").stringValue() + " | " + result.get("scenario").stringValue();
	}

	private static Set<String> strings(JsonNode array) {
		return array.valueStream().map(JsonNode::stringValue).collect(Collectors.toSet());
	}

}
