package io.archsim.benchmarks.report;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.docker.Stacks;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * An experiment's ladder on the validation page: each step measured against predicted,
 * with a cache's hit ratio and a queue's throughput and message age where the stack has
 * them, and notes on unstable and failed steps.
 */
final class LadderTable {

	private static final String KAFKA = "kafka";

	private LadderTable() {
	}

	/**
	 * The ladder's table and notes.
	 * @param experiment the experiment
	 * @param comparison its latest run's comparison
	 * @return the lines
	 */
	static List<String> of(Experiment experiment, JsonNode comparison) {
		boolean cache = Stacks.standard().isCache(experiment.stack());
		boolean queue = experiment.id().equals(KAFKA);
		List<String> lines = new ArrayList<>();
		lines.add("| Offered req/s | p50 meas / pred (ms) | p95 meas / pred (ms) | p95 error | p99 meas / pred (ms) | "
				+ "Success meas / pred | Throughput meas / pred |" + (cache ? " Hit ratio meas / pred |" : "")
				+ (queue ? " Consumed/s meas / pred | Message age p95 meas / pred (ms) |" : ""));
		lines.add("| --- | --- | --- | --- | --- | --- | --- |" + (cache ? " --- |" : "")
				+ (queue ? " --- | --- |" : ""));
		comparison.get("rows")
			.forEach((row) -> lines.add(row(row) + (cache ? cacheCells(row) : "") + (queue ? queueCells(row) : "")));
		List<String> notes = notes(comparison);
		if (!notes.isEmpty()) {
			lines.add("");
			lines.addAll(notes);
		}
		return lines;
	}

	private static String row(JsonNode row) {
		JsonNode measured = row.get("measured");
		JsonNode predicted = row.get("predicted");
		String offered = Figures.rate(row.get("offeredRps"))
				+ (PythonText.truthy(PythonValues.get(row, "unstable")) ? " (unstable)" : "");
		return "| " + offered + " | " + pair(measured, predicted, "p50") + " | " + pair(measured, predicted, "p95")
				+ " | " + Figures.percent(row.get("error").get("p95")) + " | " + pair(measured, predicted, "p99")
				+ " | " + Figures.magnitude(measured.get("successRate")) + " / "
				+ Figures.magnitude(predicted.get("successRate")) + " | " + Figures.rate(measured.get("throughputRps"))
				+ " / " + Figures.rate(predicted.get("throughputRps")) + " |";
	}

	// a cache stack's hit ratios
	private static String cacheCells(JsonNode row) {
		return " " + Figures.magnitude(PythonValues.get(row.get("measured"), "cacheHitRatio")) + " / "
				+ Figures.magnitude(PythonValues.get(row.get("predicted"), "cacheHitRatio")) + " |";
	}

	// the queue's consumed rate and message age
	private static String queueCells(JsonNode row) {
		JsonNode was = queueOf(row.get("measured"));
		JsonNode model = queueOf(row.get("predicted"));
		return " " + Figures.rate(PythonValues.get(was, "consumedRps")) + " / "
				+ Figures.rate(PythonValues.get(model, "consumedRps")) + " | "
				+ Figures.milliseconds(PythonValues.get(was, "p95MessageAgeMs")) + " / "
				+ Figures.milliseconds(PythonValues.get(model, "p95MessageAgeMs")) + " |";
	}

	private static String pair(JsonNode measured, JsonNode predicted, String latency) {
		return Figures.milliseconds(measured.get(latency)) + " / " + Figures.milliseconds(predicted.get(latency));
	}

	// a step's queue figures, or none
	private static JsonNode queueOf(JsonNode side) {
		JsonNode queue = PythonValues.get(side, "queue");
		return PythonText.truthy(queue) ? queue : PythonValues.none();
	}

	private static List<String> notes(JsonNode comparison) {
		List<String> notes = new ArrayList<>();
		List<JsonNode> rows = comparison.get("rows").valueStream().toList();
		if (rows.stream().anyMatch((row) -> PythonText.truthy(PythonValues.get(row, "unstable")))) {
			notes.add("Unstable: the step's repeats' p95s differ by more than 2x across the objective (a repeat with "
					+ "no successful request counts as slower than any).");
		}
		rows.stream()
			.filter((row) -> PythonText.truthy(PythonValues.get(row, "failedRepeats")))
			.forEach((row) -> notes.add(failedRepeats(row)));
		return notes;
	}

	private static String failedRepeats(JsonNode row) {
		String outcome = PythonValues.isNone(row.get("measured").get("p95")) ? "so this step has no measured latency"
				: "so the step's latency is the slower of the other repeats";
		return "At " + Figures.rate(row.get("offeredRps")) + " req/s, " + PythonText.str(row.get("failedRepeats"))
				+ " of " + PythonText.str(row.get("repeats")) + " repeats had no successful request (every request "
				+ "failed or was dropped). Such a repeat counts as failed: it has no latency, and in the step's median "
				+ "it counts as slower than any measured repeat, " + outcome + ".";
	}

}
