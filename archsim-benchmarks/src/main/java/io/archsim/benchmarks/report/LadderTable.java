package io.archsim.benchmarks.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.docker.Stacks;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * An experiment's ladder on the validation page: each step measured against predicted, in
 * load order, with a cache's hit ratio and a queue's throughput and message age where the
 * stack has them, the share of its requests k6 dropped, the steps the run added to close
 * in on its knee marked, and notes on those, on unstable and failed steps and on what a
 * step with dropped requests measured.
 */
final class LadderTable {

	private static final String KAFKA = "kafka";

	private static final String RATE = "offeredRps";

	/** The steps a run added to close in on its knee, in its comparison. */
	private static final String SEARCH = "kneeSearchRows";

	private static final String KNEE_SEARCH = "Knee search: a step the run added between two of the ladder's, or "
			+ "below them, to close in on the measured knee. It counts towards the knees, and is left out of the "
			+ "chart and of the p95 and resource statistics, which are the ladder's.";

	private LadderTable() {
	}

	/**
	 * The ladder's table and notes.
	 * @param experiment the experiment
	 * @param comparison its latest run's comparison
	 * @param dropped the share of each step's requests k6 dropped in that run
	 * @return the lines
	 */
	static List<String> of(Experiment experiment, JsonNode comparison, DroppedSteps dropped) {
		boolean cache = Stacks.standard().isCache(experiment.stack());
		boolean queue = experiment.id().equals(KAFKA);
		Set<JsonNode> search = Collections.newSetFromMap(new IdentityHashMap<>());
		comparison.path(SEARCH).forEach(search::add);
		List<JsonNode> rows = inLoadOrder(comparison);
		List<String> lines = new ArrayList<>(header(cache, queue));
		rows.forEach((row) -> lines.add(row(row, dropped, search.contains(row)) + (cache ? cacheCells(row) : "")
				+ (queue ? queueCells(row) : "")));
		List<String> notes = notes(rows, search, dropped);
		if (!notes.isEmpty()) {
			lines.add("");
			lines.addAll(notes);
		}
		return lines;
	}

	// the table's two heading lines, with a cache's and a queue's columns
	private static List<String> header(boolean cache, boolean queue) {
		return List.of(
				"| Offered req/s | p50 meas / pred (ms) | p95 meas / pred (ms) | p95 error | p99 meas / pred (ms) | "
						+ "Success meas / pred | Dropped | Throughput meas / pred |"
						+ (cache ? " Hit ratio meas / pred |" : "")
						+ (queue ? " Consumed/s meas / pred | Message age p95 meas / pred (ms) |" : ""),
				"| --- | --- | --- | --- | --- | --- | --- | --- |" + (cache ? " --- |" : "")
						+ (queue ? " --- | --- |" : ""));
	}

	// the ladder's rows and the knee search's, in load order
	private static List<JsonNode> inLoadOrder(JsonNode comparison) {
		List<JsonNode> rows = new ArrayList<>(comparison.get("rows").valueStream().toList());
		comparison.path(SEARCH).forEach(rows::add);
		rows.sort((left, right) -> PythonValues.compare(left.get(RATE), right.get(RATE)));
		return rows;
	}

	private static String row(JsonNode row, DroppedSteps dropped, boolean search) {
		JsonNode measured = row.get("measured");
		JsonNode predicted = row.get("predicted");
		List<String> marks = new ArrayList<>();
		if (PythonText.truthy(PythonValues.get(row, "unstable"))) {
			marks.add("unstable");
		}
		if (search) {
			marks.add("knee search");
		}
		String offered = Figures.rate(row.get(RATE)) + (marks.isEmpty() ? "" : " (" + String.join(", ", marks) + ")");
		return "| " + offered + " | " + pair(measured, predicted, "p50") + " | " + pair(measured, predicted, "p95")
				+ " | " + Figures.percent(row.get("error").get("p95")) + " | " + pair(measured, predicted, "p99")
				+ " | " + Figures.magnitude(measured.get("successRate")) + " / "
				+ Figures.magnitude(predicted.get("successRate")) + " | " + dropped.at(row.get(RATE)) + " | "
				+ Figures.rate(measured.get("throughputRps")) + " / " + Figures.rate(predicted.get("throughputRps"))
				+ " |";
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

	// on unstable and failed steps, on the knee search's when the run made one, and on
	// what a step with dropped requests measured
	private static List<String> notes(List<JsonNode> rows, Set<JsonNode> search, DroppedSteps dropped) {
		List<String> notes = new ArrayList<>();
		if (rows.stream().anyMatch((row) -> PythonText.truthy(PythonValues.get(row, "unstable")))) {
			notes.add("Unstable: the step's repeats' p95s differ by more than 2x across the objective (a repeat with "
					+ "no successful request counts as slower than any).");
		}
		rows.stream()
			.filter((row) -> PythonText.truthy(PythonValues.get(row, "failedRepeats")))
			.forEach((row) -> notes.add(failedRepeats(row)));
		if (!search.isEmpty()) {
			notes.add(KNEE_SEARCH);
		}
		dropped.note().ifPresent(notes::add);
		return notes;
	}

	private static String failedRepeats(JsonNode row) {
		String outcome = PythonValues.isNone(row.get("measured").get("p95")) ? "so this step has no measured latency"
				: "so the step's latency is the slower of the other repeats";
		return "At " + Figures.rate(row.get(RATE)) + " req/s, " + PythonText.str(row.get("failedRepeats")) + " of "
				+ PythonText.str(row.get("repeats")) + " repeats had no successful request (every request "
				+ "failed or was dropped). Such a repeat counts as failed: it has no latency, and in the step's median "
				+ "it counts as slower than any measured repeat, " + outcome + ".";
	}

}
