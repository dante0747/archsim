package io.archsim.benchmarks.report;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.baselines.Baselines;
import io.archsim.benchmarks.comparison.RelativeError;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonStatistics;
import io.archsim.benchmarks.values.PythonValues;

/**
 * The simulator's knees against the simple estimates' (ADR-0087) over the held-out runs,
 * as {@code accuracy.json}'s summary holds them (ADR-0101): over every counted run that
 * repeats a held-out experiment's latest conditions, the median size and the worst of the
 * simulator's knee errors and of each estimate's, and in how many experiments each of the
 * two was the nearer to the measured knee. An experiment's runs share most of their error
 * (ADR-0094), so a sign test weighs experiments, one vote each, not runs.
 */
final class AgainstEstimatesHeldOut {

	/** The simulator's key among a run's errors, and in the summary. */
	private static final String SIMULATOR = "simulator";

	/**
	 * The sign test's level: one of the two is called the nearer when a split of the
	 * experiments at least as uneven as theirs, either way, would come less often than
	 * this if each experiment were a fair coin's toss between them. No split of five
	 * experiments or fewer gets below it.
	 */
	private static final BigDecimal SIGNIFICANCE = new BigDecimal("0.05");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private AgainstEstimatesHeldOut() {
	}

	/**
	 * The comparison over the held-out runs.
	 * @param heldOut the held-out experiments' names, in the page's order
	 * @param history every kept run, by experiment ({@link RunHistory#of})
	 * @param results the results folder, which holds each run's {@code baselines.json}
	 * @return the runs and experiments counted, the simulator's errors and each
	 * estimate's; empty without a counted held-out run
	 */
	static Optional<ObjectNode> of(List<String> heldOut, Map<String, List<JsonNode>> history, Path results) {
		Map<String, List<Run>> experiments = new LinkedHashMap<>();
		for (String experiment : heldOut) {
			List<Run> runs = Repeatability.counted(history.getOrDefault(experiment, List.of()))
				.stream()
				.map((run) -> Run.of(experiment, run, results))
				.toList();
			if (!runs.isEmpty()) {
				experiments.put(experiment, runs);
			}
		}
		if (experiments.isEmpty()) {
			return Optional.empty();
		}
		List<Run> runs = experiments.values().stream().flatMap(List::stream).toList();
		ObjectNode json = NODES.objectNode().put("runs", runs.size()).put("experiments", experiments.size());
		Errors simulator = Errors.of(runs, SIMULATOR);
		json.set(SIMULATOR, simulator.figures().set("text", simulator.text()));
		ArrayNode estimates = json.putArray("estimates");
		Baselines.ESTIMATES.forEach((estimate) -> estimates.add(comparison(runs, experiments.values(), estimate)));
		return Optional.of(json);
	}

	// an estimate's errors, and in how many experiments it and the simulator were the
	// nearer
	private static ObjectNode comparison(List<Run> runs, Collection<List<Run>> experiments, String estimate) {
		Errors errors = Errors.of(runs, estimate);
		int simulatorNearer = (int) experiments.stream()
			.filter((experiment) -> nearer(experiment, SIMULATOR, estimate))
			.count();
		int estimateNearer = (int) experiments.stream()
			.filter((experiment) -> nearer(experiment, estimate, SIMULATOR))
			.count();
		ObjectNode json = NODES.objectNode()
			.put("estimate", estimate)
			.put("name", BaselineTexts.standard().name(estimate));
		json.setAll(errors.figures());
		json.put("simulatorNearer", simulatorNearer)
			.put("estimateNearer", estimateNearer)
			.put("verdict", verdict(simulatorNearer, estimateNearer));
		return json.set("text", errors.text());
	}

	// whether one's knees were nearer the measured ones than the other's over an
	// experiment's runs that have both, by the median of their errors' sizes; neither was
	// when they tie or no run has both
	private static boolean nearer(List<Run> experiment, String one, String other) {
		List<Run> both = experiment.stream().filter((run) -> run.hasError(one) && run.hasError(other)).toList();
		if (both.isEmpty()) {
			return false;
		}
		return medianSize(both, one).doubleValue() < medianSize(both, other).doubleValue();
	}

	// the median size of the errors of runs that all have one
	private static JsonNode medianSize(List<Run> runs, String key) {
		return PythonValues.median(runs.stream().map((run) -> PythonValues.abs(run.error(key))).toList());
	}

	/**
	 * Whether the simulator or an estimate was the nearer to the measured knee in a
	 * typical experiment: a two-sided sign test on the experiments where one of them was,
	 * those where the two were as near left out. It calls neither the nearer with five
	 * experiments or fewer.
	 * @param simulatorNearer the experiments where the simulator was the nearer
	 * @param estimateNearer the experiments where the estimate was
	 * @return {@code simulator nearer} or {@code estimate nearer}, or {@code level} when
	 * the experiments cannot tell them apart
	 */
	static String verdict(int simulatorNearer, int estimateNearer) {
		int experiments = simulatorNearer + estimateNearer;
		BigInteger ways = BigInteger.ZERO;
		for (int votes = Math.max(simulatorNearer, estimateNearer); votes <= experiments; votes++) {
			ways = ways.add(PythonStatistics.comb(experiments, votes));
		}
		// either way: twice those ways over every way, exact since the divisor is a power
		// of two
		BigDecimal chance = new BigDecimal(ways.shiftLeft(1))
			.divide(new BigDecimal(BigInteger.ONE.shiftLeft(experiments)));
		if (chance.compareTo(SIGNIFICANCE) >= 0) {
			return "level";
		}
		return (simulatorNearer > estimateNearer) ? "simulator nearer" : "estimate nearer";
	}

	/**
	 * A held-out run's knee errors: the simulator's and each estimate's.
	 *
	 * @param experiment its experiment
	 * @param dir its folder, as the run history names it
	 * @param errors each error by its key, the simulator's under {@link #SIMULATOR}; None
	 * where there is none
	 */
	private record Run(String experiment, String dir, Map<String, JsonNode> errors) {

		Run {
			errors = Map.copyOf(errors);
		}

		static Run of(String experiment, JsonNode run, Path results) {
			JsonNode measured = PythonValues.get(run.get("measuredKnee"), "kneeRps");
			JsonNode file = ResultFiles.read(RunHistory.keptRun(results, experiment, run).file(KeptRun.BASELINES))
				.orElse(PythonValues.none());
			Map<String, JsonNode> errors = new HashMap<>();
			errors.put(SIMULATOR, PythonValues.get(run, "kneeError"));
			Baselines.ESTIMATES.forEach(
					(estimate) -> errors.put(estimate, RelativeError.of(Baselines.knee(file, estimate), measured)));
			return new Run(experiment, run.get("dir").stringValue(), errors);
		}

		JsonNode error(String key) {
			return this.errors.getOrDefault(key, PythonValues.none());
		}

		boolean hasError(String key) {
			return !PythonValues.isNone(error(key));
		}

		double absoluteError(String key) {
			return Math.abs(error(key).doubleValue());
		}

		ObjectNode where() {
			return NODES.objectNode().put("experiment", this.experiment).put("dir", this.dir);
		}

	}

	/**
	 * The simulator's or an estimate's knee errors over the runs that have one.
	 *
	 * @param runs how many runs have one
	 * @param medianAbsError the median of their sizes, or None
	 * @param worstError the largest, the first of them, or None
	 * @param worst the run it was found in, or None
	 */
	private record Errors(int runs, JsonNode medianAbsError, JsonNode worstError, JsonNode worst) {

		static Errors of(List<Run> runs, String key) {
			List<Run> with = runs.stream().filter((run) -> run.hasError(key)).toList();
			Optional<Run> worst = with.stream()
				.reduce((farthest, run) -> (run.absoluteError(key) > farthest.absoluteError(key)) ? run : farthest);
			return new Errors(with.size(), medianSize(with, key),
					worst.map((run) -> run.error(key)).orElse(PythonValues.none()),
					worst.<JsonNode>map(Run::where).orElse(PythonValues.none()));
		}

		ObjectNode figures() {
			ObjectNode json = NODES.objectNode().put("runs", this.runs);
			json.set("medianAbsError", this.medianAbsError);
			json.set("worstError", this.worstError);
			return json.set("worst", this.worst);
		}

		// the median to a tenth, where two close ones would round to the same whole
		// percent
		ObjectNode text() {
			return NODES.objectNode()
				.put("medianAbsError", Figures.magnitudeTenths(this.medianAbsError))
				.put("worstError", Figures.percent(this.worstError));
		}

	}

}
