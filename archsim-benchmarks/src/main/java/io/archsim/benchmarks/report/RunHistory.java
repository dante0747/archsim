package io.archsim.benchmarks.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.comparison.Knees;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.KeptRuns;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * Every kept run of each experiment, oldest first and the latest last, as the page's run
 * history and {@code accuracy.json} record it: where its results are, its protocol,
 * machine and engine, its knees, its predicted range, and its own checks of criteria 2
 * and 3a.
 */
final class RunHistory {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final Pattern PROTOCOL = Pattern.compile("\\((v\\d+)\\)");

	/**
	 * The history's name for an experiment's latest run, kept in the experiment's folder.
	 */
	private static final String LATEST = "latest";

	/** What ends the history's name for an older run's folder: {@code run-3/}. */
	private static final String FOLDER_END = "/";

	/** What the history keeps of a run's predicted range. */
	private static final List<String> RANGE_KEYS = List.of("lowRps", "highRps", "nominalCoverage", "containsMeasured",
			"method");

	private RunHistory() {
	}

	/**
	 * The kept runs with a comparison, by experiment in the harness's order.
	 * @param results the results folder
	 * @return each experiment's runs, oldest first
	 */
	static Map<String, List<JsonNode>> of(Path results) {
		Map<String, List<JsonNode>> history = new LinkedHashMap<>();
		for (KeptRun run : KeptRuns.of(results)) {
			ResultFiles.read(run.file(KeptRun.COMPARISON))
				.ifPresent((comparison) -> history.computeIfAbsent(run.experiment(), (name) -> new ArrayList<>())
					.add(entry(run, comparison)));
		}
		return history;
	}

	private static ObjectNode entry(KeptRun run, JsonNode comparison) {
		JsonNode calibration = ResultFiles.read(run.file(KeptRun.CALIBRATION)).orElse(PythonValues.none());
		JsonNode environment = ResultFiles.read(run.file(KeptRun.ENVIRONMENT)).orElse(NODES.objectNode());
		JsonNode predictions = ResultFiles.read(run.file(KeptRun.PREDICTIONS)).orElse(NODES.objectNode());
		JsonNode calibratedAt = calibration.path("at");
		String folder = run.directory().getFileName().toString();
		ObjectNode entry = NODES.objectNode()
			.put("dir", folder.equals(run.experiment()) ? LATEST : folder + FOLDER_END)
			.put("protocol", protocolOf(calibration));
		entry.set("calibratedAt", calibratedAt);
		entry.put("machine", PythonText.str(PythonValues.get(environment, "cpu")));
		entry.set("os", PythonValues.get(environment, "os"));
		entry.set("powerMode", PythonValues.get(environment, "powerMode"));
		entry.put("harnessCaveat", PageTexts.standard().harnessCaveat(run.experiment(), calibratedAt).isPresent());
		entry.set("engineVersion", PythonValues.get(predictions, "engineVersion"));
		entry.set("measuredKnee", comparison.get("measuredKnee"));
		entry.set("predictedKnee", comparison.get("predictedKnee"));
		entry.set("kneeError", comparison.get("kneeError"));
		entry.set("kneeRange", range(PythonValues.get(comparison, "kneeRange")));
		entry.set("checks", checks(comparison));
		return entry;
	}

	/**
	 * A run of the history, back in its folder: the experiment's own for the latest,
	 * {@code run-N/} under it for an older one.
	 * @param results the results folder
	 * @param experiment the run's experiment
	 * @param run the run, as the history records it
	 * @return the kept run
	 */
	static KeptRun keptRun(Path results, String experiment, JsonNode run) {
		Path latest = results.resolve(experiment);
		String dir = run.get("dir").stringValue();
		if (LATEST.equals(dir)) {
			return new KeptRun(experiment, latest);
		}
		// a path drops the slash that ends an older run's name
		return new KeptRun(experiment, latest.resolve(dir));
	}

	/**
	 * The protocol a calibration ran under, {@code v4}, from its method; {@code v1} for
	 * run 1, which predates the field.
	 * @param calibration {@code calibration.json}, or None
	 * @return the protocol
	 */
	static String protocolOf(JsonNode calibration) {
		String method = calibration.path("method").stringValue("");
		Matcher protocol = PROTOCOL.matcher(method);
		return protocol.find() ? protocol.group(1) : "v1";
	}

	/**
	 * One run's own knee error and checks of criteria 2 and 3a: at or below its measured
	 * knee, the steps whose p95 missed and the unstable ones; and its disturbed steps.
	 * @param comparison the run's {@code comparison.json}
	 * @return the checks
	 */
	static ObjectNode checks(JsonNode comparison) {
		List<JsonNode> rows = comparison.get("rows").valueStream().toList();
		List<JsonNode> below = Knees.atOrBelowKnee(rows, comparison.get("measuredKnee"));
		ArrayNode misses = NODES.arrayNode();
		ArrayNode unstable = NODES.arrayNode();
		for (JsonNode row : below) {
			if (!Tiers.p95Within(row.get("measured").get("p95"), row.get("predicted").get("p95"))) {
				misses.add(row.get("offeredRps"));
			}
			if (PythonText.truthy(PythonValues.get(row, "unstable"))) {
				unstable.add(row.get("offeredRps"));
			}
		}
		ObjectNode checks = NODES.objectNode().set("kneeError", comparison.get("kneeError"));
		checks.put("steps", below.size());
		checks.set("misses", misses);
		checks.set("unstable", unstable);
		checks.put("p95Pass", !below.isEmpty() && misses.isEmpty());
		checks.put("stablePass", !below.isEmpty() && unstable.isEmpty());
		checks.set("disturbed", disturbed(rows));
		return checks;
	}

	/**
	 * The steps that missed the objective below a higher step that met it, in load order.
	 * A stable stack does not get faster with more load, so such a miss was a
	 * disturbance, not the stack's limit: a run with one is kept and shown, but not
	 * counted.
	 * @param rows a comparison's rows
	 * @return the disturbed steps' loads
	 */
	static ArrayNode disturbed(List<JsonNode> rows) {
		ArrayNode steps = NODES.arrayNode();
		Knees.disturbedSteps(rows).forEach(steps::add);
		return steps;
	}

	/**
	 * Whether a kept run counts towards the criteria, the repeatability and the spread:
	 * not when it was disturbed.
	 * @param run the run, as the history records it
	 * @return whether it counts
	 */
	static boolean counted(JsonNode run) {
		JsonNode checks = PythonValues.get(run, "checks");
		return !(checks.isObject() && PythonText.truthy(PythonValues.get(checks, "disturbed")));
	}

	private static JsonNode range(JsonNode range) {
		if (!PythonText.truthy(range)) {
			return PythonValues.none();
		}
		ObjectNode kept = NODES.objectNode();
		RANGE_KEYS.forEach((key) -> kept.set(key, PythonValues.get(range, key)));
		return kept;
	}

}
