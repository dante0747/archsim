package io.archsim.engine.parity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

/**
 * The port plan's bar for a template case: over its seeds, the Java simulator's median
 * p50, p95, throughput and each part's utilization fall inside the frozen engine's range
 * and within 2% of its median.
 */
final class ParityBar {

	/** How far the Java median may be from the Python one, as a share of it. */
	private static final double WITHIN = 0.02;

	private static final String UTILIZATION = "utilization";

	private static final String SUMMARY = "summary";

	private static final String NODES = "nodes";

	private ParityBar() {
	}

	/**
	 * Each metric of a case judged.
	 * @param python the frozen engine's figures of each seed's run
	 * @param java the Java simulator's figures of the same runs
	 * @return each metric Python measured, in order: p50, p95, throughput, then each
	 * part's utilization
	 */
	static List<MetricVerdict> judge(List<JsonNode> python, List<JsonNode> java) {
		List<MetricVerdict> verdicts = new ArrayList<>();
		for (Metric metric : metrics(python)) {
			List<Double> pythonValues = values(python, metric.reader());
			if (!pythonValues.isEmpty()) {
				verdicts.add(MetricVerdict.of(metric.name(), pythonValues, values(java, metric.reader())));
			}
		}
		return verdicts;
	}

	/**
	 * The median, as Python's {@code statistics.median} takes it: the mean of the middle
	 * two of an even count.
	 * @param values the values, at least one
	 * @return the median
	 */
	static double median(List<Double> values) {
		List<Double> sorted = values.stream().sorted().toList();
		int middle = sorted.size() / 2;
		return (sorted.size() % 2 == 1) ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
	}

	/**
	 * Whether a Java median is within 2% of Python's.
	 * @param pythonMedian the Python median
	 * @param javaMedian the Java median
	 * @return whether it is
	 */
	static boolean withinTwoPercent(double pythonMedian, double javaMedian) {
		return Math.abs(javaMedian - pythonMedian) <= WITHIN * Math.abs(pythonMedian);
	}

	/**
	 * How far a Java median is from Python's, as a share of it.
	 * @param pythonMedian the Python median
	 * @param javaMedian the Java median
	 * @return the share; zero when both are zero, infinite when only Python's is
	 */
	static double distance(double pythonMedian, double javaMedian) {
		double gap = Math.abs(javaMedian - pythonMedian);
		return (gap == 0) ? 0.0 : gap / Math.abs(pythonMedian);
	}

	// p50, p95, throughput, then the utilization of each part that has one
	private static List<Metric> metrics(List<JsonNode> runs) {
		List<Metric> metrics = new ArrayList<>(
				List.of(new Metric("p50", (run) -> run.path(SUMMARY).path("latencyMs").path("p50")),
						new Metric("p95", (run) -> run.path(SUMMARY).path("latencyMs").path("p95")),
						new Metric("throughput", (run) -> run.path(SUMMARY).path("throughputRps"))));
		Set<String> parts = new LinkedHashSet<>();
		runs.forEach((run) -> run.path(NODES)
			.properties()
			.stream()
			.filter((node) -> node.getValue().path(UTILIZATION).isNumber())
			.forEach((node) -> parts.add(node.getKey())));
		parts.forEach((part) -> metrics
			.add(new Metric(UTILIZATION + " " + part, (run) -> run.path(NODES).path(part).path(UTILIZATION))));
		return metrics;
	}

	private static List<Double> values(List<JsonNode> runs, Function<JsonNode, JsonNode> read) {
		return runs.stream().map(read).filter(JsonNode::isNumber).map(JsonNode::doubleValue).toList();
	}

	/**
	 * A metric the bar judges, and where a run's figures hold it.
	 *
	 * @param name its name: {@code p50}, {@code p95}, {@code throughput} or
	 * {@code utilization <part>}
	 * @param reader its value in a run's figures
	 */
	private record Metric(String name, Function<JsonNode, JsonNode> reader) {

	}

}
