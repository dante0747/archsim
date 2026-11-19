package io.archsim.benchmarks.report;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonStatistics;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * How an experiment's latest conditions repeated over its kept runs: the runs that repeat
 * the latest one, their knee errors (the median, the range, and the mean of their sizes,
 * which no run's miss hides in), and how often the predicted range held the measured knee
 * against how often it claims to.
 */
final class Repeatability {

	/**
	 * A range is called too narrow (too wide) when so few (so many) of the runs' measured
	 * knees inside it would happen less often than this at its nominal coverage.
	 */
	private static final double COVERAGE_ALPHA = 0.05;

	/** What makes two runs repeat each other. */
	private static final List<String> CONDITIONS = List.of("protocol", "machine", "os", "powerMode", "engineVersion");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final int EXPECTED_DECIMALS = 2;

	private Repeatability() {
	}

	/**
	 * The kept runs that repeat the latest one: same protocol, machine, OS build, power
	 * mode and engine. Their spread is run-to-run noise; across any of those it is not.
	 * @param runs the kept runs, the latest last
	 * @return the runs alike, in their order
	 */
	static List<JsonNode> comparable(List<? extends JsonNode> runs) {
		if (runs.isEmpty()) {
			return List.of();
		}
		JsonNode last = runs.getLast();
		return runs.stream()
			.filter((run) -> CONDITIONS.stream()
				.allMatch((key) -> PythonValues.get(run, key).equals(PythonValues.get(last, key))))
			.map(JsonNode.class::cast)
			.toList();
	}

	/**
	 * How the latest run's conditions repeated, without the disturbed runs: the knee
	 * errors, their median and range, and how often the measured knee fell inside the
	 * predicted range.
	 * @param runs the kept runs, the latest last
	 * @return the repeatability; empty without runs
	 */
	static Optional<ObjectNode> of(List<? extends JsonNode> runs) {
		List<JsonNode> alike = comparable(runs);
		if (alike.isEmpty()) {
			return Optional.empty();
		}
		List<JsonNode> group = alike.stream().filter(RunHistory::counted).toList();
		List<JsonNode> errors = group.stream()
			.map((run) -> PythonValues.get(run, "kneeError"))
			.filter((error) -> !PythonValues.isNone(error))
			.toList();
		JsonNode median = PythonValues.median(errors);
		ObjectNode repeatability = NODES.objectNode()
			.put("runs", group.size())
			.put("disturbed", alike.size() - group.size());
		repeatability.set("protocol", PythonValues.get(alike.getLast(), "protocol"));
		repeatability.set("engineVersion", PythonValues.get(alike.getLast(), "engineVersion"));
		repeatability.putArray("errors").addAll(errors);
		repeatability.set("medianError", median);
		repeatability.set("minError", errors.isEmpty() ? PythonValues.none() : PythonValues.min(errors));
		repeatability.set("maxError", errors.isEmpty() ? PythonValues.none() : PythonValues.max(errors));
		repeatability.set("meanAbsError", meanAbs(errors));
		coverage(repeatability, group);
		ObjectNode text = repeatability.putObject("text").put("medianError", Figures.percent(median));
		text.put("errors", errorsText(repeatability, errors.size()));
		text.put("meanAbsError", Figures.magnitude(repeatability.get("meanAbsError")));
		int withRange = repeatability.get("withRange").intValue();
		text.put("coverage",
				(withRange == 0) ? Figures.NONE : repeatability.get("inRange").intValue() + " of " + withRange);
		return Optional.of(repeatability);
	}

	/**
	 * The mean of the errors' sizes: unlike the median of a few runs, it counts every
	 * run's miss in full.
	 * @param errors the runs' knee errors
	 * @return the mean absolute error; None without errors
	 */
	static JsonNode meanAbs(List<? extends JsonNode> errors) {
		if (errors.isEmpty()) {
			return PythonValues.none();
		}
		return PythonValues.divide(PythonValues.sum(errors.stream().map(PythonValues::abs).toList()),
				PythonValues.integer(errors.size()));
	}

	// the runs with a range, those whose measured knee it held, and
	// whether that is as many as the ranges' nominal coverages expect
	private static void coverage(ObjectNode repeatability, List<JsonNode> group) {
		List<JsonNode> ranged = group.stream()
			.map((run) -> PythonValues.get(run, "kneeRange"))
			.filter(PythonText::truthy)
			.toList();
		int hits = (int) ranged.stream()
			.filter((range) -> PythonText.truthy(PythonValues.get(range, "containsMeasured")))
			.count();
		JsonNode expected = PythonValues.sum(ranged.stream().map(Repeatability::nominalCoverage).toList());
		repeatability.put("withRange", ranged.size()).put("inRange", hits);
		repeatability.set("nominalCoverage",
				ranged.isEmpty() ? PythonValues.none() : PythonValues.get(ranged.getLast(), "nominalCoverage"));
		repeatability.set("expectedInRange", PythonValues.round(expected, EXPECTED_DECIMALS));
		repeatability.set("coverageVerdict", verdictNode(hits, ranged.size(), expected.doubleValue()));
	}

	// a range's nominal coverage, or 0.0 when it gave none
	private static JsonNode nominalCoverage(JsonNode range) {
		JsonNode coverage = PythonValues.get(range, "nominalCoverage");
		return PythonText.truthy(coverage) ? coverage : PythonValues.number(0.0);
	}

	private static String errorsText(ObjectNode repeatability, int errors) {
		String low = Figures.percent(repeatability.get("minError"));
		return (errors <= 1) ? low : low + " to " + Figures.percent(repeatability.get("maxError"));
	}

	/**
	 * Whether the predicted range held the measured knee about as often as it claims, by
	 * the binomial tails at the ranges' mean nominal coverage: with 12 runs at 50%, 2 or
	 * fewer hits are too narrow; at 80%, 6 or fewer.
	 * @param hits the runs whose measured knee was inside the range
	 * @param runs the runs with a range
	 * @param expected the hits the ranges' nominal coverages expect, summed
	 * @return {@code too narrow}, {@code too wide} or {@code as claimed}; empty without
	 * runs
	 */
	static Optional<String> coverageVerdict(int hits, int runs, double expected) {
		if (runs == 0) {
			return Optional.empty();
		}
		double share = Math.min(Math.max(expected / runs, 0.0), 1.0);
		if (tail(runs, share, 0, hits) < COVERAGE_ALPHA) {
			return Optional.of("too narrow");
		}
		return Optional.of((tail(runs, share, hits, runs) < COVERAGE_ALPHA) ? "too wide" : "as claimed");
	}

	/**
	 * {@link #coverageVerdict} as JSON.
	 * @param hits the runs whose measured knee was inside the range
	 * @param runs the runs with a range
	 * @param expected the hits the ranges' nominal coverages expect
	 * @return the verdict, or None
	 */
	static JsonNode verdictNode(int hits, int runs, double expected) {
		return coverageVerdict(hits, runs, expected).<JsonNode>map(NODES::stringNode).orElse(PythonValues.none());
	}

	// the chance that from fewest to most of the runs, both included, hit
	private static double tail(int runs, double share, int fewest, int most) {
		return PythonValues
			.sum(IntStream.rangeClosed(fewest, most)
				.mapToObj((inside) -> PythonValues.number(probability(runs, inside, share)))
				.toList())
			.doubleValue();
	}

	// comb(runs, inside) * share ** inside * (1 -
	// share) ** (runs - inside), in Python's order
	private static double probability(int runs, int inside, double share) {
		BigInteger ways = PythonStatistics.comb(runs, inside);
		return ways.doubleValue() * StrictMath.pow(share, inside) * StrictMath.pow(1 - share, runs - inside);
	}

	/**
	 * How often the predicted range held the measured knee over every experiment's runs
	 * under its latest conditions: the runs with a range, those inside it, the hits their
	 * nominal coverages expect, the nominal coverage when every range claims the same,
	 * and the verdict.
	 * @param repeatabilities each experiment's repeatability, or None
	 * @return the summary
	 */
	static ObjectNode coverageSummary(List<JsonNode> repeatabilities) {
		List<JsonNode> given = repeatabilities.stream()
			.filter((repeatability) -> PythonText.truthy(repeatability)
					&& PythonText.truthy(PythonValues.get(repeatability, "withRange")))
			.toList();
		int runs = given.stream().mapToInt((repeatability) -> repeatability.get("withRange").intValue()).sum();
		int hits = given.stream().mapToInt((repeatability) -> repeatability.get("inRange").intValue()).sum();
		JsonNode expected = PythonValues.sum(given.stream()
			.map((repeatability) -> repeatability.has("expectedInRange") ? repeatability.get("expectedInRange")
					: PythonValues.number(0.0))
			.toList());
		List<JsonNode> nominals = new ArrayList<>();
		for (JsonNode repeatability : given) {
			JsonNode nominal = PythonValues.get(repeatability, "nominalCoverage");
			if (nominals.stream().noneMatch((seen) -> sameValue(seen, nominal))) {
				nominals.add(nominal);
			}
		}
		ObjectNode summary = NODES.objectNode().put("withRange", runs).put("inRange", hits);
		summary.set("expectedInRange", PythonValues.round(expected, EXPECTED_DECIMALS));
		summary.set("nominalCoverage", (nominals.size() == 1) ? nominals.getFirst() : PythonValues.none());
		summary.set("verdict", verdictNode(hits, runs, expected.doubleValue()));
		return summary;
	}

	private static boolean sameValue(JsonNode left, JsonNode right) {
		if (PythonValues.isNone(left) || PythonValues.isNone(right)) {
			return PythonValues.isNone(left) && PythonValues.isNone(right);
		}
		return PythonValues.compare(left, right) == 0;
	}

}
