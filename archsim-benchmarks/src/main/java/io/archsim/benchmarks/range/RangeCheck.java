package io.archsim.benchmarks.range;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.IntStream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.comparison.Knees;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;

/**
 * A range fit from some calibrations and some runs' errors, and whether the range holds
 * each run's measured knee at each nominal coverage, fitted three ways: in-sample,
 * leaving out the run, and leaving out its experiment. Never with a run's own error in
 * its fit outside in-sample.
 */
final class RangeCheck {

	/** The ways a run's range is fitted to be checked on it. */
	static final Map<String, String> FOLDS = folds();

	/** The nominal coverages the check reports: these and the range's own, in order. */
	static final List<Double> LEVELS = List.copyOf(new TreeSet<>(List.of(0.5, 0.8, 0.9, KneeRanges.COVERAGE)));

	private static final String IN_SAMPLE = "inSample";

	private static final String LEAVE_RUN_OUT = "leaveRunOut";

	private static final int DECIMALS = 4;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private RangeCheck() {
	}

	/**
	 * A range fit: the variation of some calibrations ({@link VariationFit}) and the
	 * model's error over some runs ({@link ModelError}: between experiments and within
	 * them, and the two together), each run's calibration spread from that variation.
	 * @param calibrations one row per calibration: its experiment, source and scales
	 * @param runs the runs
	 * @return the fit: the variation's fields and {@code model}
	 */
	static ObjectNode foldFit(List<JsonNode> calibrations, List<CountedRun> runs) {
		ObjectNode fit = VariationFit.of(calibrations);
		List<Double> spreads = runs.stream().map((run) -> KneeRanges.calibrationSigma(run.sensitivity(), fit)).toList();
		List<Double> errors = runs.stream().map(CountedRun::error).toList();
		List<String> experiments = runs.stream().map(CountedRun::experiment).toList();
		ModelError.Fit model = ModelError.of(errors, spreads, experiments);
		fit.putObject("model")
			.put("sigma", PythonRound.round(model.sigma(), DECIMALS))
			.put("between", PythonRound.round(model.between(), DECIMALS))
			.put("within", PythonRound.round(model.within(), DECIMALS))
			.put("runs", runs.size())
			.put("experiments", new HashSet<>(experiments).size());
		return fit;
	}

	/**
	 * The check.
	 * @param calibrations one row per calibration
	 * @param runs the runs
	 * @return the counts per fold and level, and each run's ranges at the nominal
	 * coverage
	 */
	static Result check(List<JsonNode> calibrations, List<CountedRun> runs) {
		ObjectNode counts = NODES.objectNode();
		for (String fold : FOLDS.keySet()) {
			ObjectNode byLevel = counts.putObject(fold);
			LEVELS.forEach((level) -> byLevel.putArray(PythonFormat.format(level, "g")).add(0).add(0));
		}
		List<ObjectNode> detail = runs.stream().map((run) -> NODES.objectNode()).toList();
		ObjectNode full = foldFit(calibrations, runs);
		Fitted fitted = new Fitted(full, calibrations, runs);
		for (String fold : FOLDS.keySet()) {
			for (int index = 0; index < runs.size(); index++) {
				checkRun(counts.get(fold), detail.get(index), fold, new Placed(index, fitted));
			}
		}
		return new Result(counts, detail);
	}

	// one run in a fold, at every level
	private static void checkRun(JsonNode foldCounts, ObjectNode runDetail, String fold, Placed placed) {
		ObjectNode fit = fit(fold, placed.index(), placed.fitted());
		CountedRun run = placed.fitted().runs().get(placed.index());
		LEVELS.forEach((level) -> count(foldCounts, runDetail, new Checked(fold, level, run, fit)));
	}

	// the fit a run's range is checked with, in a fold
	private static ObjectNode fit(String fold, int index, Fitted fitted) {
		CountedRun run = fitted.runs().get(index);
		if (fold.equals(IN_SAMPLE)) {
			return fitted.full();
		}
		if (fold.equals(LEAVE_RUN_OUT)) {
			return foldFit(
					fitted.calibrations()
						.stream()
						.filter((row) -> !row.get("source").stringValue().equals(run.source()))
						.toList(),
					IntStream.range(0, fitted.runs().size())
						.filter((other) -> other != index)
						.mapToObj(fitted.runs()::get)
						.toList());
		}
		return foldFit(
				fitted.calibrations()
					.stream()
					.filter((row) -> !row.get("experiment").stringValue().equals(run.experiment()))
					.toList(),
				fitted.runs().stream().filter((other) -> !other.experiment().equals(run.experiment())).toList());
	}

	// whether the run's range at a level holds its measured knee; its range at the
	// nominal coverage kept
	private static void count(JsonNode foldCounts, ObjectNode runDetail, Checked checked) {
		CountedRun run = checked.run();
		Optional<ObjectNode> range = KneeRanges.of(run.knee(), run.sensitivity(), checked.fit(), checked.level());
		Optional<Boolean> holds = range.flatMap((found) -> Knees.inRange(found, run.measuredKnee()));
		ArrayNode tally = (ArrayNode) foldCounts.get(PythonFormat.format(checked.level(), "g"));
		tally.set(1, NODES.numberNode(tally.get(1).intValue() + 1));
		tally.set(0, NODES.numberNode(tally.get(0).intValue() + (holds.orElse(false) ? 1 : 0)));
		if (checked.level() == KneeRanges.COVERAGE && range.isPresent()) {
			ObjectNode at = runDetail.putObject(checked.fold());
			for (String key : List.of("lowRps", "highRps", "calibrationSigma", "modelSigma")) {
				at.set(key, range.get().get(key));
			}
			at.set("holds", holds.<JsonNode>map(NODES::booleanNode).orElse(NODES.nullNode()));
		}
	}

	private static Map<String, String> folds() {
		Map<String, String> folds = new LinkedHashMap<>();
		folds.put(IN_SAMPLE, "fitted on every run, then checked on each");
		folds.put(LEAVE_RUN_OUT, "each run checked with a fit that left out its calibration and its error");
		folds.put("leaveExperimentOut",
				"each run checked with a fit that left out every calibration and error of its experiment");
		return Collections.unmodifiableMap(folds);
	}

	/**
	 * The check's result.
	 *
	 * @param counts per fold and level, the runs whose range held their measured knee and
	 * the runs checked
	 * @param detail each run's range at the nominal coverage, per fold
	 */
	record Result(ObjectNode counts, List<ObjectNode> detail) {

		Result {
			detail = List.copyOf(detail);
		}

	}

	/**
	 * The fit over every run and what it was fitted on.
	 *
	 * @param full the fit
	 * @param calibrations its calibrations
	 * @param runs its runs
	 */
	private record Fitted(ObjectNode full, List<JsonNode> calibrations, List<CountedRun> runs) {

	}

	/**
	 * A run's place among the runs of a fit.
	 *
	 * @param index its place
	 * @param fitted the fit over every run
	 */
	private record Placed(int index, Fitted fitted) {

	}

	/**
	 * A run checked in a fold at a level.
	 *
	 * @param fold the fold
	 * @param level the coverage
	 * @param run the run
	 * @param fit the fit its range comes from
	 */
	private record Checked(String fold, double level, CountedRun run, ObjectNode fit) {

	}

}
