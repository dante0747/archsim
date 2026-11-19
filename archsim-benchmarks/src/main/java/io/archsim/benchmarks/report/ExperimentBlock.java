package io.archsim.benchmarks.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * An experiment's section of the validation page: its latest run's calibration, the model
 * inputs it gave, its chart, every ladder step measured against predicted, notes on
 * unstable and failed steps and on those k6 dropped requests in, and the knees with the
 * steps the measured one lies between.
 */
final class ExperimentBlock {

	private ExperimentBlock() {
	}

	/**
	 * An experiment's section, from its latest run.
	 * @param experiment the experiment
	 * @param results the results folder
	 * @return the section's text
	 */
	static String of(Experiment experiment, Path results) {
		Optional<JsonNode> calibration = LatestRuns.load(results, experiment.id(), KeptRun.CALIBRATION);
		Optional<JsonNode> predictions = LatestRuns.load(results, experiment.id(), KeptRun.PREDICTIONS);
		Optional<JsonNode> comparison = LatestRuns.load(results, experiment.id(), KeptRun.COMPARISON);
		if (calibration.isEmpty() || predictions.isEmpty() || comparison.isEmpty()) {
			return "_No results for " + experiment.id() + " yet: run `./gradlew :archsim-benchmarks:run --args=\""
					+ experiment.stack() + "\"`._";
		}
		List<String> lines = new ArrayList<>(head(experiment, calibration.get(), predictions.get(), comparison.get()));
		lines.addAll(inputs(calibration.get()));
		lines.addAll(LadderTable.of(experiment, comparison.get(), DroppedSteps
			.of(LatestRuns.load(results, experiment.id(), KeptRun.MEASURED).orElse(PythonValues.none()))));
		lines.addAll(knees(experiment, comparison.get()));
		PageTexts.standard()
			.harnessCaveat(experiment.id(), PythonValues.get(calibration.get(), "at"))
			.ifPresent((caveat) -> lines.addAll(List.of("", "**Harness caveat.** " + caveat)));
		return String.join("\n", lines);
	}

	private static List<String> head(Experiment experiment, JsonNode calibration, JsonNode predictions,
			JsonNode comparison) {
		JsonNode objective = comparison.get("objective");
		return List.of(
				"**" + comparison.get("title").stringValue() + ".** Calibrated " + PythonText.str(calibration.get("at"))
						+ ", predicted " + PythonText.str(predictions.get("at")) + " (engine "
						+ PythonText.str(PythonValues.get(predictions, "engineVersion"))
						+ "), measured afterwards. Objective: p95 <= " + Figures.format(objective.get("p95Ms"), "g")
						+ " ms with >= "
						+ Figures.format(PythonValues.multiply(objective.get("minSuccess"),
								PythonValues.integer(Figures.PERCENT)), "g")
						+ "% success.",
				"",
				"![" + experiment.id() + ": predicted and measured p95 and throughput](validation/" + experiment.id()
						+ ".svg)",
				"", source(calibration), "", "| Input | Value | How it was measured |", "| --- | --- | --- |");
	}

	// where the inputs came from: a staircase (protocol v5 on), two passes, or one
	private static String source(JsonNode calibration) {
		JsonNode rateLow = PythonValues.get(calibration, "passes").path("low").path("rateRps");
		JsonNode rateHigh = highPassRate(calibration);
		JsonNode stairs = PythonValues.get(calibration, "staircaseRps");
		if (!PythonValues.isNone(stairs)) {
			List<JsonNode> rates = stairs.valueStream().toList();
			JsonNode replicates = PythonValues.get(calibration, "inputReplicates");
			int sets = PythonText.truthy(replicates) ? replicates.size() : 0;
			return "Model inputs from a staircase of calibration passes at " + Figures.rates(rates)
					+ " (each rate up and down again): the median of " + sets + " replicate sets from the "
					+ Figures.rate(rates.getLast()) + " req/s passes, CPU per request net of the "
					+ Figures.format(calibration.get("idle").get("seconds"), "g") + " s at rest:";
		}
		if (!PythonValues.isNone(rateLow) && !PythonValues.isNone(rateHigh)) {
			String highText = Figures.format(rateHigh, "g");
			return "Model inputs from the calibration passes at " + Figures.format(rateLow, "g") + " and " + highText
					+ " req/s (timings from the " + highText
					+ " req/s pass, CPU per request from the slope between the two):";
		}
		return "Model inputs from the calibration pass at " + Figures.format(rateHigh, "g") + " req/s:";
	}

	// the inputs that have a value, and how each was measured
	private static List<String> inputs(JsonNode calibration) {
		List<String> lines = new ArrayList<>();
		calibration.get("inputs").properties().forEach((input) -> {
			JsonNode item = input.getValue();
			if (item.isObject() && item.has("value")) {
				lines.add("| `" + input.getKey() + "` | " + inputValue(input.getKey(), item.get("value")) + " | "
						+ PythonText.str(item.get("how")) + " |");
			}
		});
		JsonNode low = lowLoad(calibration);
		lines.add("");
		lines.add("Measured in the calibration pass at "
				+ Figures.rate(low.has("rateRps") ? low.get("rateRps") : highPassRate(calibration)) + " req/s: p50 "
				+ Figures.milliseconds(PythonValues.get(low, "p50")) + " ms, p95 "
				+ Figures.milliseconds(PythonValues.get(low, "p95")) + " ms.");
		lines.add("");
		return lines;
	}

	/**
	 * A model input's value as the inputs table writes it: a distribution, a load curve,
	 * or a number, in milliseconds when its name says so.
	 * @param name the input's name
	 * @param value its value
	 * @return the value in words
	 */
	static String inputValue(String name, JsonNode value) {
		String kind = value.isObject() ? PythonValues.get(value, "kind").stringValue("") : "";
		if ("lognormal".equals(kind)) {
			return "lognormal, median " + Figures.format(value.get("median"), "g") + " ms, p95 "
					+ Figures.format(value.get("p95"), "g") + " ms";
		}
		if ("exponential".equals(kind)) {
			return "exponential, mean " + Figures.format(value.get("mean"), "g") + " ms";
		}
		if (value.isArray()) {
			List<String> points = value.valueStream()
				.map((point) -> "x" + Figures.format(point.get("factor"), ".2f") + " at "
						+ Figures.format(point.get("rps"), ",.0f"))
				.toList();
			return points.isEmpty() ? "none" : String.join(", ", points) + " req/s";
		}
		return Figures.format(value, "g") + (name.endsWith("Ms") ? " ms" : "");
	}

	private static List<String> knees(Experiment experiment, JsonNode comparison) {
		JsonNode range = PythonValues.get(comparison, "kneeRange");
		String rangeText = !PythonText.truthy(range) ? ""
				: " Its predicted " + rangeLabel(range) + " was " + Figures.rate(range.get("lowRps")) + " to "
						+ Figures.rate(range.get("highRps")) + " req/s: the measured knee fell "
						+ (PythonText.truthy(PythonValues.get(range, "containsMeasured")) ? "inside" : "outside")
						+ " it.";
		return List.of("", "Knee: measured " + Figures.knee(comparison.get("measuredKnee")) + " req/s"
				+ between(comparison.get("measuredKnee")) + ", predicted "
				+ Figures.knee(comparison.get("predictedKnee")) + " req/s on the same steps ("
				+ Figures.percent(comparison.get("kneeError")) + "); ArchSim's own search before measuring put it at "
				+ Figures.rate(comparison.get("predictedKneeFine")) + " req/s." + rangeText
				+ " Data: `benchmarks/results/" + experiment.id()
				+ "/` (each repeat's numbers as extracted from k6's summary; the summaries themselves "
				+ "are not kept).");
	}

	// the steps either side of a knee found between two: the last that met the objective
	// and the first that missed it
	private static String between(JsonNode knee) {
		JsonNode steps = PythonValues.get(knee, "between");
		return !PythonText.truthy(steps) ? "" : " (between the steps at " + Figures.rate(steps.get(0)) + " and "
				+ Figures.rate(steps.get(1)) + " req/s)";
	}

	/**
	 * What a predicted range is, in a few words: engine 0.14.0's came from the replicate
	 * calibration sets, W28's carries the run-to-run variation and the model's error.
	 * @param range a comparison's {@code kneeRange}
	 * @return the words
	 */
	static String rangeLabel(JsonNode range) {
		JsonNode replicates = PythonValues.get(range, "replicates");
		if (PythonText.truthy(replicates)) {
			return "range, from the " + PythonText.str(replicates) + " replicate sets,";
		}
		return Figures.magnitude(PythonValues.get(range, "nominalCoverage")) + " range";
	}

	// the top pass's rate, else the low-load measurement's
	private static JsonNode highPassRate(JsonNode calibration) {
		JsonNode high = PythonValues.get(calibration, "passes").path("high");
		return high.has("rateRps") ? high.get("rateRps") : PythonValues.get(lowLoad(calibration), "rateRps");
	}

	private static JsonNode lowLoad(JsonNode calibration) {
		JsonNode low = calibration.get("inputs").path("measuredLowLoad");
		return low.isObject() ? low : PythonValues.none();
	}

}
