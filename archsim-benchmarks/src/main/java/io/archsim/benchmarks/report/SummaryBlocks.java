package io.archsim.benchmarks.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * The validation page's tables of the latest runs and of the kept runs: the summary, the
 * repeatability, the run history and the low-load steps.
 */
final class SummaryBlocks {

	/** The page's words for a sample label. */
	static final Map<String, String> SAMPLE_TEXT = Map.of("held-out", "held out", "tuned-on", "tuned on");

	/** The ladder's low steps: at most this share of ArchSim's own predicted knee. */
	static final double LOW_LOAD_FRACTION = 0.5;

	private SummaryBlocks() {
	}

	/**
	 * Each experiment's latest run, labelled held out or tuned on.
	 * @param comparisons the latest runs' comparisons, in the page's order
	 * @param samples each experiment's sample label
	 * @return the table
	 */
	static String summary(List<JsonNode> comparisons, Map<String, String> samples) {
		List<String> lines = new ArrayList<>(List
			.of("| Experiment | Sample | Knee measured | Knee predicted | Knee error | p95 error at or below the "
					+ "measured knee (median / worst) |", "| --- | --- | --- | --- | --- | --- |"));
		for (JsonNode comparison : comparisons) {
			JsonNode below = comparison.get("belowKnee");
			String experiment = comparison.get("experiment").stringValue();
			lines.add("| [" + experiment + "](#" + experiment + ") | " + SAMPLE_TEXT.get(samples.get(experiment))
					+ " | " + Figures.knee(comparison.get("measuredKnee")) + " req/s | "
					+ Figures.knee(comparison.get("predictedKnee")) + " req/s | "
					+ Figures.percent(comparison.get("kneeError")) + " | "
					+ Figures.magnitude(below.get("medianAbsP95Error")) + " / "
					+ Figures.magnitude(below.get("maxAbsP95Error")) + " (" + PythonText.str(below.get("steps"))
					+ " steps) |");
		}
		return String.join("\n", lines);
	}

	/**
	 * Each experiment's runs that repeat its latest one: the median knee error, the
	 * errors' range, and the predicted range's coverage.
	 * @param history every kept run, by experiment
	 * @param engines the engines, which label each sample
	 * @return the table and its note
	 */
	static String repeatability(Map<String, List<JsonNode>> history, Engines engines) {
		List<String> lines = new ArrayList<>(List.of(
				"| Experiment | Sample | Runs repeating the latest | Median knee error | Knee errors | Mean absolute "
						+ "knee error | Measured knee inside the predicted range |",
				"| --- | --- | --- | --- | --- | --- | --- |"));
		Map<String, List<JsonNode>> errorsBySample = new LinkedHashMap<>();
		for (Experiment experiment : LatestRuns.inPageOrder()) {
			Repeatability.of(history.getOrDefault(experiment.id(), List.of())).ifPresent((repeated) -> {
				lines.add(repeatabilityRow(experiment, repeated, engines));
				errorsBySample.computeIfAbsent(sampleOf(experiment, repeated, engines), (sample) -> new ArrayList<>())
					.addAll(repeated.get("errors").valueStream().toList());
			});
		}
		lines.add("");
		lines.addAll(meanAbsBySample(errorsBySample));
		lines.add("The tier criteria below judge the median over at least " + Tiers.MIN_RUNS + " such runs. The mean "
				+ "absolute error counts every run's miss in full, which the median of a few runs can hide. The "
				+ "nominal coverage is how often the measured knee should fall inside the predicted range. Engine "
				+ "0.14.0's runs took the range from their three replicate calibration sets ((R - 1) / (R + 1) = "
				+ "50%), which leaves out how much a calibration moves from one day to the next and the model's own "
				+ "error; since W28 the range is an 80% one that carries both ([Protocol](#protocol), step 3). A "
				+ "disturbed run missed the objective at one load step and met it at a higher one; it is kept and "
				+ "listed under the run history, but not counted.");
		return String.join("\n", lines);
	}

	private static String repeatabilityRow(Experiment experiment, JsonNode repeated, Engines engines) {
		String coverage = (repeated.get("withRange").intValue() > 0) ? repeated.at("/text/coverage").stringValue()
				+ " runs (nominal " + Figures.magnitude(repeated.get("nominalCoverage")) + ")"
				: "no range (protocol v5 adds it)";
		int disturbed = repeated.get("disturbed").intValue();
		String leftOut = (disturbed > 0) ? "; " + disturbed + " disturbed, not counted" : "";
		String sample = sampleOf(experiment, repeated, engines);
		return "| [" + experiment.id() + "](#" + experiment.id() + ") | " + sample + " | "
				+ PythonText.str(repeated.get("runs")) + " (" + PythonText.str(repeated.get("protocol")) + ", engine "
				+ PythonText.str(repeated.get("engineVersion")) + leftOut + ") | "
				+ repeated.at("/text/medianError").stringValue() + " | " + repeated.at("/text/errors").stringValue()
				+ " | " + repeated.at("/text/meanAbsError").stringValue() + " | " + coverage + " |";
	}

	private static String sampleOf(Experiment experiment, JsonNode repeated, Engines engines) {
		String label = engines.sampleLabel(repeated.get("engineVersion"), experiment);
		return Objects.requireNonNull(SAMPLE_TEXT.get(label), label);
	}

	// each sample's mean absolute knee error over every run of the table, and a blank
	// line
	private static List<String> meanAbsBySample(Map<String, List<JsonNode>> errorsBySample) {
		List<String> lines = new ArrayList<>();
		errorsBySample.forEach((sample, errors) -> {
			if (!errors.isEmpty()) {
				lines.add("Mean absolute knee error over every such run, " + sample + ": "
						+ Figures.magnitude(Repeatability.meanAbs(errors)) + " (" + errors.size()
						+ ((errors.size() == 1) ? " run)." : " runs)."));
				lines.add("");
			}
		});
		return lines;
	}

	/**
	 * Every kept run of each experiment, oldest first.
	 * @param history every kept run, by experiment
	 * @return the table
	 */
	static String history(Map<String, List<JsonNode>> history) {
		List<String> lines = new ArrayList<>(List
			.of("| Experiment | Results | Protocol | Calibrated (UTC) | Knee measured | Knee predicted | Knee error "
					+ "| Counted |", "| --- | --- | --- | --- | --- | --- | --- | --- |"));
		for (Experiment experiment : LatestRuns.inPageOrder()) {
			for (JsonNode run : history.getOrDefault(experiment.id(), List.of())) {
				String folder = run.get("dir").stringValue();
				String where = "latest".equals(folder) ? "latest (`results/" + experiment.id() + "/`)"
						: "`" + folder + "`";
				JsonNode calibratedAt = run.get("calibratedAt");
				lines.add("| " + experiment.id() + " | " + where + " | " + run.get("protocol").stringValue() + " | "
						+ (PythonText.truthy(calibratedAt) ? calibratedAt.stringValue() : Figures.NONE) + " | "
						+ Figures.knee(run.get("measuredKnee")) + " req/s | " + Figures.knee(run.get("predictedKnee"))
						+ " req/s | " + Figures.percent(run.get("kneeError")) + " | " + countedText(run) + " |");
			}
		}
		return String.join("\n", lines);
	}

	/**
	 * The history's "Counted" cell: {@code yes}, or no for a disturbed run, with the
	 * steps it missed.
	 * @param run a kept run
	 * @return the cell's text
	 */
	static String countedText(JsonNode run) {
		JsonNode checks = PythonValues.get(run, "checks");
		JsonNode steps = checks.isObject() ? PythonValues.get(checks, "disturbed") : PythonValues.none();
		if (!PythonText.truthy(steps)) {
			return "yes";
		}
		return "no: missed at " + Figures.rates(steps.valueStream().toList()) + ", met higher";
	}

	/**
	 * p95 at the ladder's low steps, predicted against measured, in milliseconds and
	 * relative.
	 * @param comparisons the latest runs' comparisons, in the page's order
	 * @return the table
	 */
	static String lowLoad(List<JsonNode> comparisons) {
		List<String> lines = new ArrayList<>(List.of(
				"| Experiment | Offered req/s | p95 measured (ms) | p95 predicted (ms) | Predicted - measured |",
				"| --- | --- | --- | --- | --- |"));
		for (JsonNode comparison : comparisons) {
			JsonNode fine = comparison.get("predictedKneeFine");
			for (JsonNode row : lowLoadRows(comparison)) {
				JsonNode measured = row.get("measured").get("p95");
				JsonNode predicted = row.get("predicted").get("p95");
				String difference = (PythonValues.isNone(measured) || PythonValues.isNone(predicted)) ? Figures.NONE
						: Figures.format(PythonValues.subtract(predicted, measured), "+.2f") + " ms ("
								+ Figures.percent(row.get("error").get("p95")) + ")";
				lines
					.add("| " + comparison.get("experiment").stringValue() + " | " + Figures.rate(row.get("offeredRps"))
							+ " (" + Figures.format(PythonValues.divide(row.get("offeredRps"), fine), ".2g")
							+ "x the predicted knee) | " + Figures.milliseconds(measured) + " | "
							+ Figures.milliseconds(predicted) + " | " + difference + " |");
			}
		}
		return String.join("\n", lines);
	}

	/**
	 * The ladder's low steps: at most half of ArchSim's own predicted knee.
	 * @param comparison a comparison
	 * @return its low steps; none without a predicted knee
	 */
	static List<JsonNode> lowLoadRows(JsonNode comparison) {
		JsonNode fine = comparison.get("predictedKneeFine");
		if (!PythonText.truthy(fine)) {
			return List.of();
		}
		double limit = Math.rint(LOW_LOAD_FRACTION * fine.doubleValue());
		return comparison.get("rows")
			.valueStream()
			.filter((row) -> row.get("offeredRps").doubleValue() <= limit)
			.toList();
	}

}
