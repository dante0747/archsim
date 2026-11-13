package io.archsim.benchmarks.comparison;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Objective;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * A run's measurements against its predictions, step by step ({@code comparison.json}):
 * each step's latency, success, throughput and errors, the measured and predicted knees
 * on the same steps, the p95 errors at or below the measured knee, whether the predicted
 * range held the measured knee, and each container's CPU and memory where the model
 * predicted them.
 */
public final class Comparison {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final String RATE = "offeredRps";

	private static final String RESOURCES = "resources";

	private static final String STEPS = "steps";

	/** What a row reads of a measured and a predicted step. */
	private static final List<String> COMPARED = List.of("p50", "p95", "p99", "successRate", "throughputRps");

	/** The metrics a row has an error for. */
	private static final List<String> WITH_ERROR = List.of("p50", "p95", "p99", "throughputRps");

	/** What a row adds of a measured step that has it, and the predicted step's. */
	private static final List<String> WHEN_MEASURED = List.of("cacheHitRatio", "queue");

	private Comparison() {
	}

	/**
	 * Compares a run's measurements with its predictions.
	 * @param experiment the experiment
	 * @param predictions {@code predictions.json}
	 * @param measured {@code measured.json}
	 * @param laterResources {@code resources-predicted.json} for a run whose predictions
	 * recorded no CPU or memory, else None; a run with neither is compared on latency
	 * alone
	 * @return the comparison
	 */
	public static ObjectNode compare(Experiment experiment, JsonNode predictions, JsonNode measured,
			JsonNode laterResources) {
		List<ObjectNode> steps = measuredSteps(measured);
		ModelledResources modelled = ModelledResources.of(predictions, laterResources);
		List<JsonNode> rows = rows(steps, byRate(predictions.get(STEPS)), modelled);
		ObjectNode measuredKnee = Knees.gridKnee(steps);
		ObjectNode predictedKnee = Knees.gridKnee(predictions.get(STEPS).valueStream().toList());
		ObjectNode comparison = NODES.objectNode().put("experiment", experiment.id()).put("title", experiment.title());
		comparison.putObject("objective").put("p95Ms", Objective.P95_MS).put("minSuccess", Objective.MIN_SUCCESS);
		comparison.set("measuredKnee", measuredKnee);
		comparison.set("predictedKnee", predictedKnee);
		comparison.set("predictedKneeFine", predictions.get("knee").get("kneeRps"));
		comparison.set("kneeError", RelativeError.of(predictedKnee.get("kneeRps"), measuredKnee.get("kneeRps")));
		comparison.set("belowKnee", belowKnee(Knees.atOrBelowKnee(rows, measuredKnee)));
		comparison.putArray("rows").addAll(rows);
		range(predictions, measuredKnee).ifPresent((range) -> comparison.set("kneeRange", range));
		if (modelled.any()) {
			resources(steps, Knees.atOrBelowBothKnees(rows, measuredKnee, predictedKnee), modelled)
				.ifPresent((resources) -> comparison.set(RESOURCES, resources));
		}
		return comparison;
	}

	/**
	 * Each measured step aggregated from its repeats, so that results recorded before a
	 * change to the aggregation are compared as new ones are. A step of run 1 had no
	 * repeats: the step is its one run.
	 * @param measured {@code measured.json}
	 * @return the steps
	 */
	public static List<ObjectNode> measuredSteps(JsonNode measured) {
		List<ObjectNode> steps = new ArrayList<>();
		for (JsonNode step : measured.get(STEPS)) {
			JsonNode repeats = PythonValues.get(step, "runs");
			List<JsonNode> runs = PythonText.truthy(repeats) ? repeats.valueStream().toList() : List.of(step);
			ObjectNode aggregated = NODES.objectNode().set(RATE, step.get(RATE));
			aggregated.setAll(StepAggregation.aggregate(runs));
			steps.add(aggregated);
		}
		return steps;
	}

	private static List<JsonNode> rows(List<ObjectNode> steps, Map<Double, JsonNode> predicted,
			ModelledResources modelled) {
		List<JsonNode> rows = new ArrayList<>();
		for (ObjectNode step : steps) {
			JsonNode prediction = predicted.get(step.get(RATE).doubleValue());
			if (prediction == null) {
				throw new IllegalArgumentException("no prediction for the measured step at " + step.get(RATE));
			}
			ObjectNode row = row(step, prediction);
			if (modelled.any()) {
				ResourceComparison.atStep(PythonValues.get(step, RESOURCES), modelled.at(prediction, step))
					.ifPresent((resources) -> row.set(RESOURCES, resources));
			}
			rows.add(row);
		}
		return rows;
	}

	private static ObjectNode row(ObjectNode measured, JsonNode predicted) {
		ObjectNode row = NODES.objectNode().set(RATE, measured.get(RATE));
		ObjectNode was = row.putObject("measured");
		ObjectNode model = row.putObject("predicted");
		COMPARED.forEach((key) -> {
			was.set(key, PythonValues.get(measured, key));
			model.set(key, PythonValues.get(predicted, key));
		});
		ObjectNode error = row.putObject("error");
		WITH_ERROR.forEach((key) -> error.set(key, RelativeError.of(model.get(key), was.get(key))));
		row.set("unstable", measured.get("unstable"));
		row.set("repeats", measured.get("repeats"));
		row.set("failedRepeats", measured.get("failedRepeats"));
		WHEN_MEASURED.stream().filter(measured::has).forEach((key) -> {
			was.set(key, measured.get(key));
			model.set(key, PythonValues.get(predicted, key));
		});
		return row;
	}

	// the steps at or below the measured knee, and their median and largest p95 errors
	private static ObjectNode belowKnee(List<JsonNode> below) {
		List<JsonNode> errors = below.stream()
			.map((row) -> row.get("error").get("p95"))
			.filter((error) -> !PythonValues.isNone(error))
			.map(PythonValues::abs)
			.toList();
		ObjectNode summary = NODES.objectNode().put("steps", below.size());
		summary.set("medianAbsP95Error", PythonValues.median(errors));
		summary.set("maxAbsP95Error", errors.isEmpty() ? PythonValues.none() : PythonValues.max(errors));
		return summary;
	}

	// the predicted range, and whether it held the measured knee
	private static Optional<ObjectNode> range(JsonNode predictions, JsonNode measuredKnee) {
		JsonNode range = PythonValues.get(predictions, "kneeRange");
		if (!PythonText.truthy(range)) {
			return Optional.empty();
		}
		ObjectNode held = ((ObjectNode) range).deepCopy();
		held.set("containsMeasured",
				Knees.inRange(range, measuredKnee).<JsonNode>map(BooleanNode::valueOf).orElse(PythonValues.none()));
		return Optional.of(held);
	}

	private static Optional<ObjectNode> resources(List<ObjectNode> steps, List<JsonNode> belowBothKnees,
			ModelledResources modelled) {
		Set<String> methods = new TreeSet<>();
		steps.stream()
			.filter((step) -> step.has(RESOURCES))
			.forEach((step) -> methods.add(step.get(RESOURCES).get("method").stringValue()));
		if (methods.isEmpty()) {
			return Optional.empty();
		}
		ObjectNode resources = NODES.objectNode()
			.put("measuredBy", (methods.size() == 1) ? methods.iterator().next() : "mixed");
		resources.set("predictedBy", modelled.predictedBy());
		resources.set("belowKnee", ResourceComparison.belowKnees(belowBothKnees));
		return Optional.of(resources);
	}

	private static Map<Double, JsonNode> byRate(JsonNode steps) {
		Map<Double, JsonNode> byRate = new HashMap<>();
		steps.forEach((step) -> byRate.put(step.get(RATE).doubleValue(), step));
		return byRate;
	}

	/**
	 * The model's CPU and memory for a run: in its predictions, or simulated again later
	 * from its calibration when its predictions recorded none.
	 *
	 * @param inPredictions whether its predictions recorded them
	 * @param later the later simulation's resources by load, empty without it
	 * @param laterFile {@code resources-predicted.json}, or None
	 */
	private record ModelledResources(boolean inPredictions, Map<Double, JsonNode> later, JsonNode laterFile) {

		static ModelledResources of(JsonNode predictions, JsonNode laterFile) {
			Map<Double, JsonNode> later = new HashMap<>();
			if (!PythonValues.isNone(laterFile)) {
				laterFile.path(STEPS)
					.forEach((step) -> later.put(step.get(RATE).doubleValue(), PythonValues.get(step, RESOURCES)));
			}
			boolean inPredictions = predictions.get(STEPS).valueStream().anyMatch((step) -> step.has(RESOURCES));
			return new ModelledResources(inPredictions, later, laterFile);
		}

		boolean any() {
			return this.inPredictions || !this.later.isEmpty();
		}

		JsonNode at(JsonNode prediction, JsonNode step) {
			return this.inPredictions ? PythonValues.get(prediction, RESOURCES)
					: this.later.getOrDefault(step.get(RATE).doubleValue(), PythonValues.none());
		}

		// predictions.json itself, or the later simulation: its engine, date and method
		JsonNode predictedBy() {
			if (this.inPredictions) {
				return NODES.stringNode("predictions");
			}
			ObjectNode by = ((ObjectNode) this.laterFile).deepCopy();
			by.remove(STEPS);
			return by;
		}

	}

}
