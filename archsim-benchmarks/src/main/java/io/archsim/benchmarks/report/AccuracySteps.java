package io.archsim.benchmarks.report;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * The steps of an experiment's latest run as {@code accuracy.json} holds them: each
 * ladder step, the low-load steps and each container's CPU and memory, every number with
 * the validation page's text beside it.
 */
final class AccuracySteps {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final String RATE = "offeredRps";

	private static final String TEXT = "text";

	private AccuracySteps() {
	}

	/**
	 * One ladder step: measured and predicted p95 and success, and whether the p95 is
	 * within criterion 2.
	 * @param row the comparison's row
	 * @param atOrBelowKnee whether it is at or below the measured knee
	 * @return the step
	 */
	static ObjectNode step(JsonNode row, boolean atOrBelowKnee) {
		JsonNode measured = row.get("measured");
		JsonNode predicted = row.get("predicted");
		JsonNode error = row.get("error");
		ObjectNode step = NODES.objectNode().set(RATE, row.get(RATE));
		step.put("atOrBelowKnee", atOrBelowKnee);
		step.put("unstable", PythonText.truthy(PythonValues.get(row, "unstable")));
		step.putObject("measured").set("p95Ms", measured.get("p95")).set("successRate", measured.get("successRate"));
		step.putObject("predicted").set("p95Ms", predicted.get("p95")).set("successRate", predicted.get("successRate"));
		step.set("p95Error", error.get("p95"));
		step.put("p95Within", Tiers.p95Within(measured.get("p95"), predicted.get("p95")));
		step.putObject(TEXT)
			.put("offered", Figures.rate(row.get(RATE)))
			.put("measuredP95", Figures.milliseconds(measured.get("p95")))
			.put("predictedP95", Figures.milliseconds(predicted.get("p95")))
			.put("p95Error", Figures.percent(error.get("p95")))
			.put("measuredSuccess", Figures.magnitude(measured.get("successRate")))
			.put("predictedSuccess", Figures.magnitude(predicted.get("successRate")));
		return step;
	}

	/**
	 * One low-load step, as the low-load table shows it.
	 * @param row the comparison's row
	 * @param fine the engine's own predicted knee
	 * @return the step
	 */
	static ObjectNode lowLoad(JsonNode row, JsonNode fine) {
		JsonNode measured = row.get("measured").get("p95");
		JsonNode predicted = row.get("predicted").get("p95");
		JsonNode fraction = PythonValues.divide(row.get(RATE), fine);
		boolean both = !PythonValues.isNone(measured) && !PythonValues.isNone(predicted);
		JsonNode difference = both ? PythonValues.subtract(predicted, measured) : PythonValues.none();
		ObjectNode step = NODES.objectNode().set(RATE, row.get(RATE));
		step.set("fractionOfPredictedKnee", fraction);
		step.set("measuredP95Ms", measured);
		step.set("predictedP95Ms", predicted);
		step.set("diffMs", difference);
		step.putObject(TEXT)
			.put("offered", Figures.rate(row.get(RATE)))
			.put("fraction", Figures.format(fraction, ".2g") + "x")
			.put("measuredP95", Figures.milliseconds(measured))
			.put("predictedP95", Figures.milliseconds(predicted))
			.put("diff", both ? Figures.format(difference, "+.2f") + " ms" : Figures.NONE)
			.put("p95Error", Figures.percent(row.get("error").get("p95")));
		return step;
	}

	/**
	 * One container at one step: measured and predicted busy cores and memory.
	 * @param cell the comparison's row's resources for the container
	 * @return the cell
	 */
	static ObjectNode resourceCell(JsonNode cell) {
		JsonNode measured = cell.get("measured");
		JsonNode predicted = cell.get("predicted");
		JsonNode error = cell.get("error");
		ObjectNode json = NODES.objectNode().set("measuredCores", measured.get("cores"));
		json.set("predictedCores", predicted.get("cores"));
		json.set("coresError", error.get("cores"));
		json.set("measuredMemoryMiB", measured.get("memoryMiB"));
		json.set("predictedMemoryMiB", predicted.get("memoryMiB"));
		json.set("memoryError", error.get("memory"));
		json.putObject(TEXT)
			.put("measuredCores", Figures.cores(measured.get("cores")))
			.put("predictedCores", Figures.cores(predicted.get("cores")))
			.put("coresError", Figures.percent(error.get("cores")))
			.put("measuredMemory", Figures.memory(measured.get("memoryMiB")))
			.put("predictedMemory", Figures.memory(predicted.get("memoryMiB")))
			.put("memoryError", Figures.percent(error.get("memory")));
		return json;
	}

	/**
	 * An experiment's CPU and memory, predicted against measured: per part, the highest
	 * step at or below both knees and the errors up to it; and every step that has them.
	 * @param comparison the latest run's comparison
	 * @return the resources, or None when the run has none
	 */
	static JsonNode resources(JsonNode comparison) {
		JsonNode resources = PythonValues.get(comparison, "resources");
		if (!PythonText.truthy(resources)) {
			return PythonValues.none();
		}
		JsonNode top = ResourcesBlock.topBelowKnees(comparison).orElse(PythonValues.none());
		List<JsonNode> rows = comparison.get("rows").valueStream().filter((row) -> row.has("resources")).toList();
		JsonNode by = resources.get("predictedBy");
		ObjectNode json = NODES.objectNode().set("measuredBy", resources.get("measuredBy"));
		json.put("measuredByText", PageTexts.standard().measuredBy(resources.get("measuredBy").stringValue()));
		json.put("predictedBy",
				(by.isString() && "predictions".equals(by.stringValue())) ? "predictions" : "re-simulated");
		json.put("predictedByText", ResourcesBlock.predictedByText(by));
		json.set("predictedEngineVersion", by.isObject() ? PythonValues.get(by, "engineVersion") : PythonValues.none());
		json.put("memoryMeasured", ResourcesBlock.memoryMeasured(comparison));
		json.set("topRps", PythonValues.isNone(top) ? PythonValues.none() : top.get(RATE));
		ArrayNode parts = json.putArray("parts");
		resources.get("belowKnee")
			.properties()
			.forEach((part) -> parts.add(part(part.getKey(), part.getValue(), top, rows)));
		json.set("steps", resourceSteps(comparison, rows));
		return json;
	}

	private static ObjectNode part(String container, JsonNode summary, JsonNode top, List<JsonNode> rows) {
		JsonNode cell = top.path("resources").path(container);
		ObjectNode part = NODES.objectNode().put("container", container);
		part.put("name", PageTexts.standard().part(container));
		part.putObject("modelled")
			.put("cores", predicts(rows, container, "cores"))
			.put("memory", predicts(rows, container, "memoryMiB"));
		ObjectNode notModelled = part.putObject("notModelled");
		PageTexts texts = PageTexts.standard();
		notModelled.set("cores",
				texts.whyNoCores(container).<JsonNode>map(NODES::stringNode).orElse(PythonValues.none()));
		notModelled.set("memory",
				texts.whyNoMemory(container).<JsonNode>map(NODES::stringNode).orElse(PythonValues.none()));
		part.set("belowKnee", summary);
		part.set("top", PythonText.truthy(cell) ? resourceCell(cell) : PythonValues.none());
		part.putObject(TEXT)
			.put("medianAbsCoresError", Figures.magnitude(summary.get("medianAbsCoresError")))
			.put("maxAbsCoresError", Figures.magnitude(summary.get("maxAbsCoresError")))
			.put("medianAbsMemoryError", Figures.magnitude(summary.get("medianAbsMemoryError")));
		return part;
	}

	// whether the model predicted a figure for the container at any step
	private static boolean predicts(List<JsonNode> rows, String container, String figure) {
		return rows.stream()
			.anyMatch(
					(row) -> !PythonValues.isNone(row.get("resources").path(container).path("predicted").path(figure)));
	}

	private static ArrayNode resourceSteps(JsonNode comparison, List<JsonNode> rows) {
		Set<JsonNode> below = Collections.newSetFromMap(new IdentityHashMap<>());
		below.addAll(ResourcesBlock.resourceSteps(comparison));
		ArrayNode steps = NODES.arrayNode();
		for (JsonNode row : rows) {
			ObjectNode step = steps.addObject().set(RATE, row.get(RATE));
			step.put("atOrBelowKnee", below.contains(row));
			step.putObject(TEXT).put("offered", Figures.rate(row.get(RATE)));
			ObjectNode parts = step.putObject("parts");
			row.get("resources")
				.properties()
				.forEach((cell) -> parts.set(cell.getKey(), resourceCell(cell.getValue())));
		}
		return steps;
	}

}
