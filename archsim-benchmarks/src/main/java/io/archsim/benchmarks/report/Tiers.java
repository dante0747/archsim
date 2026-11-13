package io.archsim.benchmarks.report;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;

/**
 * The proposed tier criteria (docs/validation.md, "Proposed tier thresholds") for one
 * experiment, judged over the kept runs that repeat its latest one, at least three:
 * <ol>
 * <li>the median knee error within ±25%;</li>
 * <li>at or below the measured knee, every step's p95 within a factor of two or 3 ms of
 * the measured one, in at least two thirds of the runs;</li>
 * <li>no unstable step at or below the measured knee, in at least two thirds of the runs
 * (3a), and reproduced under the same protocol on a second machine (3b).</li>
 * </ol>
 * Disturbed runs are not counted.
 */
final class Tiers {

	/** Criterion 1: the median knee error's largest magnitude. */
	static final double KNEE_TOLERANCE = 0.25;

	/** Criterion 2: how many times off a step's predicted p95 may be. */
	static final double P95_FACTOR = 2.0;

	/** Criterion 2: how far off a step's predicted p95 may be instead (ms). */
	static final double P95_TOLERANCE_MS = 3.0;

	/** Criteria 1, 2 and 3a need at least this many runs under the same conditions. */
	static final int MIN_RUNS = 3;

	/** Criteria 2 and 3a hold in at least this share of those runs. */
	private static final double REPEAT_SHARE = 2.0 / 3;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final String PASS = "pass";

	private static final String RUNS = "runs";

	private Tiers() {
	}

	/**
	 * How many times too high or too low a predicted p95 is: 2 for both double and half
	 * the measured one, infinite when one of them is zero and the other is not.
	 * @param measured the measured p95
	 * @param predicted the predicted p95
	 * @return the factor
	 */
	static double p95Factor(double measured, double predicted) {
		if (measured == predicted) {
			return 1.0;
		}
		if (measured <= 0 || predicted <= 0) {
			return Double.POSITIVE_INFINITY;
		}
		return Math.max(predicted / measured, measured / predicted);
	}

	/**
	 * Criterion 2 for one step: the predicted p95 within a factor of two of the measured
	 * one, or within 3 ms of it, whichever is wider. A prediction too low fails as surely
	 * as one too high (ADR-0018).
	 * @param measured the measured p95, or None
	 * @param predicted the predicted p95, or None
	 * @return whether it is within; not without both
	 */
	static boolean p95Within(JsonNode measured, JsonNode predicted) {
		if (PythonValues.isNone(measured) || PythonValues.isNone(predicted)) {
			return false;
		}
		double was = measured.doubleValue();
		double model = predicted.doubleValue();
		return Math.abs(model - was) <= P95_TOLERANCE_MS || p95Factor(was, model) <= P95_FACTOR;
	}

	/**
	 * The criteria for one experiment, from its comparison and its kept runs (the last is
	 * the one compared). The misses and unstable steps listed are the compared run's.
	 * @param comparison the latest run's {@code comparison.json}
	 * @param runs the experiment's kept runs ({@link RunHistory#of})
	 * @return each criterion, whether it passes and why, and whether all do
	 */
	static ObjectNode criteria(JsonNode comparison, List<? extends JsonNode> runs) {
		ObjectNode latest = RunHistory.checks(comparison);
		List<JsonNode> group = Repeatability.comparable(runs);
		List<JsonNode> alike = new ArrayList<>();
		group.subList(0, Math.max(group.size() - 1, 0))
			.stream()
			.filter((run) -> run.has("checks"))
			.forEach((run) -> alike.add(run.get("checks")));
		alike.add(latest);
		List<JsonNode> counted = alike.stream()
			.filter((checks) -> isEmpty(PythonValues.get(checks, "disturbed")))
			.toList();
		ObjectNode criteria = NODES.objectNode();
		criteria.set("knee", knee(comparison, counted, alike.size()));
		ObjectNode p95 = criteria.putObject("p95").put(PASS, passes(counted, "p95Pass"));
		p95.set("steps", latest.get("steps"));
		p95.set("misses", latest.get("misses"));
		p95.put(RUNS, counted.size()).put("runsPassing", passing(counted, "p95Pass"));
		ObjectNode stable = criteria.putObject("stable").put(PASS, passes(counted, "stablePass"));
		stable.set("unstable", latest.get("unstable"));
		stable.put(RUNS, counted.size()).put("runsPassing", passing(counted, "stablePass"));
		criteria.set("reproduced", reproduced(runs));
		criteria.put("all", criteria.valueStream().allMatch((criterion) -> criterion.get(PASS).booleanValue()));
		return criteria;
	}

	private static ObjectNode knee(JsonNode comparison, List<JsonNode> counted, int alike) {
		JsonNode median = PythonValues.median(counted.stream()
			.map((checks) -> checks.get("kneeError"))
			.filter((error) -> !PythonValues.isNone(error))
			.toList());
		boolean pass = counted.size() >= MIN_RUNS && !PythonValues.isNone(median)
				&& Math.abs(median.doubleValue()) <= KNEE_TOLERANCE;
		ObjectNode knee = NODES.objectNode().put(PASS, pass).set("error", median);
		return knee.put(RUNS, counted.size())
			.put("disturbedRuns", alike - counted.size())
			.set("thisRun", comparison.get("kneeError"));
	}

	// criteria 2 and 3a: enough runs, and the check held in two thirds of them
	private static boolean passes(List<JsonNode> counted, String check) {
		int need = (int) Math.ceil(REPEAT_SHARE * counted.size());
		return counted.size() >= MIN_RUNS && passing(counted, check) >= need;
	}

	private static int passing(List<JsonNode> counted, String check) {
		return (int) counted.stream().filter((checks) -> checks.get(check).booleanValue()).count();
	}

	private static ObjectNode reproduced(List<? extends JsonNode> runs) {
		JsonNode protocol = runs.isEmpty() ? PythonValues.none() : runs.getLast().get("protocol");
		List<JsonNode> same = runs.stream()
			.filter((run) -> run.get("protocol").equals(protocol))
			.map(JsonNode.class::cast)
			.toList();
		Set<JsonNode> machines = new HashSet<>();
		same.forEach((run) -> machines.add(run.get("machine")));
		ObjectNode reproduced = NODES.objectNode().put(PASS, same.size() >= 2 && machines.size() >= 2);
		return reproduced.set("protocol", protocol).put(RUNS, same.size()).put("machines", machines.size());
	}

	/**
	 * Each technology, the experiments that exercise it, and whether all of them meet the
	 * thresholds.
	 * @param met each experiment, in the page's order, and whether it meets all three
	 * @return the technologies, in the order the experiments name them
	 */
	static ArrayNode technologyStatus(Map<String, Boolean> met) {
		Map<String, List<String>> experiments = new LinkedHashMap<>();
		met.keySet()
			.forEach((experiment) -> PageTexts.standard()
				.technologies(experiment)
				.forEach((technology) -> experiments.computeIfAbsent(technology, (name) -> new ArrayList<>())
					.add(experiment)));
		ArrayNode technologies = NODES.arrayNode();
		experiments.forEach((technology, names) -> {
			ObjectNode status = technologies.addObject().put("name", technology);
			ArrayNode experimentsOf = status.putArray("experiments");
			names.forEach(experimentsOf::add);
			status.put("validated", names.stream().allMatch(met::get));
		});
		return technologies;
	}

	private static boolean isEmpty(JsonNode list) {
		return PythonValues.isNone(list) || list.isEmpty();
	}

}
