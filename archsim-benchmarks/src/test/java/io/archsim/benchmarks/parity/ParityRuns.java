package io.archsim.benchmarks.parity;

import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Parity reports and template cases as {@code simulation-parity.json} holds them, knee
 * pairs and speed files, for the parity page's tests.
 */
public final class ParityRuns {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final int SEEDS = 20;

	private static final double TWO_PERCENT = 0.02;

	private ParityRuns() {
	}

	/**
	 * A case that meets the bar: every metric Python's median.
	 * @return the case
	 */
	public static ObjectNode met() {
		return scenario("base", 20, verdict("p50", new Seeds(9.0, 11.0, 10.0), 10.0),
				verdict("p95", new Seeds(18.0, 22.0, 20.0), 20.0));
	}

	/**
	 * A case whose p95 misses the bar: 5% off Python's median, inside its range.
	 * @return the case
	 */
	static ObjectNode missed() {
		return scenario("spike", 12, verdict("p50", new Seeds(9.0, 11.0, 10.0), 10.0),
				verdict("p95", new Seeds(18.0, 26.0, 20.0), 21.0));
	}

	/**
	 * One metric of a case against Python's seed range and median; {@code java} null when
	 * no Java run measured it.
	 */
	static ObjectNode verdict(String metric, Seeds python, @Nullable Double java) {
		double low = python.low();
		double high = python.high();
		double median = python.median();
		ObjectNode verdict = NODES.objectNode()
			.put("metric", metric)
			.put("pythonLow", low)
			.put("pythonHigh", high)
			.put("pythonMedian", median);
		if (java == null) {
			verdict.putNull("javaMedian").put("insideRange", false).put("withinTwoPercent", false).putNull("distance");
			return verdict;
		}
		double distance = (java == median) ? 0.0 : Math.abs(java - median) / median;
		return verdict.put("javaMedian", java)
			.put("insideRange", low <= java && java <= high)
			.put("withinTwoPercent", distance <= TWO_PERCENT)
			.put("distance", distance);
	}

	/**
	 * A template case of queueing-lab, its 20 runs, those that replayed exactly, and its
	 * metrics.
	 */
	static ObjectNode scenario(String name, int exact, ObjectNode... metrics) {
		ObjectNode scenario = NODES.objectNode()
			.put("template", "queueing-lab")
			.put("scenario", name)
			.put("runs", SEEDS)
			.put("exact", exact)
			.put("digestsEqual", exact);
		scenario.putObject("failures").put("python", 0).put("java", 0).put("mismatched", 0);
		scenario.putArray("metrics").addAll(List.of(metrics));
		List<ObjectNode> missed = List.of(metrics)
			.stream()
			.filter((metric) -> !(metric.get("insideRange").booleanValue()
					&& metric.get("withinTwoPercent").booleanValue()))
			.toList();
		scenario.put("meetsTheBar", missed.isEmpty());
		ArrayNode names = scenario.putArray("missed");
		missed.forEach((metric) -> names.add(metric.get("metric")));
		return scenario;
	}

	/**
	 * A parity report of these cases, its worst metric the one furthest from Python's.
	 */
	public static ObjectNode parity(ObjectNode... cases) {
		ObjectNode parity = NODES.objectNode()
			.put("cases", cases.length)
			.put("casesMeetingTheBar", count(cases, "meetsTheBar"))
			.put("casesInsideTheRange", all(cases, "insideRange"))
			.put("casesWithinTwoPercent", all(cases, "withinTwoPercent"))
			.put("casesWithMismatchedFailures",
					List.of(cases)
						.stream()
						.filter((scenario) -> scenario.at("/failures/mismatched").intValue() > 0)
						.count())
			.put("pythonRunsFailed",
					List.of(cases).stream().mapToInt((scenario) -> scenario.at("/failures/python").intValue()).sum())
			.put("javaRunsFailed",
					List.of(cases).stream().mapToInt((scenario) -> scenario.at("/failures/java").intValue()).sum())
			.put("casesReplayingExactly",
					List.of(cases).stream().filter((scenario) -> scenario.get("exact").intValue() == SEEDS).count())
			.put("runs", SEEDS * cases.length)
			.put("exactReplays", List.of(cases).stream().mapToInt((scenario) -> scenario.get("exact").intValue()).sum())
			.put("digestsEqual",
					List.of(cases).stream().mapToInt((scenario) -> scenario.get("digestsEqual").intValue()).sum());
		parity.set("worst", worst(cases));
		parity.put("threads", 4).put("javaSeconds", 3600.0);
		parity.putArray("results").addAll(List.of(cases));
		return parity;
	}

	private static ObjectNode worst(ObjectNode... cases) {
		ObjectNode worst = NODES.objectNode();
		double furthest = -1;
		for (ObjectNode scenario : cases) {
			for (JsonNode metric : scenario.get("metrics")) {
				double distance = metric.get("distance").isNull() ? Double.POSITIVE_INFINITY
						: metric.get("distance").doubleValue();
				if (distance > furthest) {
					furthest = distance;
					worst.set("template", scenario.get("template"));
					worst.set("scenario", scenario.get("scenario"));
					worst.set("verdict", metric);
				}
			}
		}
		return worst;
	}

	private static long count(ObjectNode[] cases, String key) {
		return List.of(cases).stream().filter((scenario) -> scenario.get(key).booleanValue()).count();
	}

	private static long all(ObjectNode[] cases, String key) {
		return List.of(cases)
			.stream()
			.filter((scenario) -> scenario.get("metrics")
				.valueStream()
				.allMatch((metric) -> metric.get(key).booleanValue()))
			.count();
	}

	/**
	 * A kept calibration's knees on both engines, with the same inputs.
	 */
	static ObjectNode kneeRow(double python, double java) {
		return NODES.objectNode()
			.put("experiment", "pg-mixed")
			.put("calibration", "2026-10-26T08-23-18Z")
			.put("python", python)
			.put("java", java)
			.put("checked", true)
			.put("resimulated", true)
			.put("sameInputs", true)
			.put("withinTwoPercent", Math.abs(java - python) <= TWO_PERCENT * python);
	}

	/**
	 * A speed file timing queueing-lab's 24,154 requests.
	 */
	static ObjectNode timed(double... walls) {
		ObjectNode timed = NODES.objectNode();
		ObjectNode template = timed.putArray("templates").addObject().put("template", "queueing-lab");
		ArrayNode seconds = template.putArray("wallSeconds");
		Arrays.stream(walls).forEach(seconds::add);
		template.put("bestWallSeconds", Arrays.stream(walls).min().orElseThrow());
		template.put("requests", 24154).put("resultDigest", "d");
		return timed;
	}

	/**
	 * Python's seed range of a metric and its median.
	 *
	 * @param low the lowest seed's
	 * @param high the highest seed's
	 * @param median the median
	 */
	record Seeds(double low, double high, double median) {

	}

}
