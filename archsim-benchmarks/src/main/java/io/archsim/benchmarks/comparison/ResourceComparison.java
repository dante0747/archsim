package io.archsim.benchmarks.comparison;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.ContainerNodes;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * Each container's measured CPU and memory against the model's node for it, at one step
 * and over the steps at or below both knees.
 */
final class ResourceComparison {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private ResourceComparison() {
	}

	/**
	 * One step's CPU and memory per container, measured against the model's.
	 * @param measured the step's measured resources, or None
	 * @param predicted the model's resources by node at that step, or None
	 * @return each container's comparison; empty when nothing was measured
	 */
	static Optional<ObjectNode> atStep(JsonNode measured, JsonNode predicted) {
		if (!PythonText.truthy(measured)) {
			return Optional.empty();
		}
		ObjectNode containers = NODES.objectNode();
		measured.get("containers").properties().forEach((container) -> {
			JsonNode node = ContainerNodes.of(container.getKey())
				.map((id) -> predicted.isObject() ? predicted.path(id) : PythonValues.none())
				.orElse(PythonValues.none());
			containers.set(container.getKey(), cell(container.getValue(), node));
		});
		return Optional.of(containers);
	}

	// a container's readings against its node's, None where the model has no node
	private static ObjectNode cell(JsonNode readings, JsonNode node) {
		ObjectNode cell = NODES.objectNode();
		ObjectNode was = cell.putObject("measured");
		was.set("cores", PythonValues.get(readings, "cores"));
		was.set("memoryMiB", PythonValues.get(readings, "workingSetMiB"));
		ObjectNode model = cell.putObject("predicted");
		model.set("cores", node.isObject() ? PythonValues.get(node, "cores") : PythonValues.none());
		model.set("memoryMiB", node.isObject() ? PythonValues.get(node, "memoryMiB") : PythonValues.none());
		ObjectNode error = cell.putObject("error");
		error.set("cores", RelativeError.of(model.get("cores"), was.get("cores")));
		error.set("memory", RelativeError.of(model.get("memoryMiB"), was.get("memoryMiB")));
		return cell;
	}

	/**
	 * Per container, how far the predicted CPU and memory were from the measured ones
	 * over the given steps: the steps it was compared at, and the median and largest
	 * absolute errors.
	 * @param rows the comparison's rows at or below both knees
	 * @return each container's summary
	 */
	static ObjectNode belowKnees(List<JsonNode> rows) {
		Set<String> names = new LinkedHashSet<>();
		rows.forEach((row) -> names.addAll(row.path("resources").propertyNames()));
		ObjectNode summaries = NODES.objectNode();
		for (String name : names) {
			List<JsonNode> cells = rows.stream()
				.filter((row) -> row.path("resources").has(name))
				.map((row) -> row.get("resources").get(name))
				.toList();
			ObjectNode summary = summaries.putObject(name).put("steps", cells.size());
			summarise(summary, cells, "cores", "Cores");
			summarise(summary, cells, "memory", "Memory");
		}
		return summaries;
	}

	private static void summarise(ObjectNode summary, List<JsonNode> cells, String key, String name) {
		List<JsonNode> errors = cells.stream()
			.map((cell) -> cell.get("error").get(key))
			.filter((error) -> !PythonValues.isNone(error))
			.map(PythonValues::abs)
			.toList();
		summary.set("medianAbs" + name + "Error", PythonValues.median(errors));
		summary.set("maxAbs" + name + "Error", errors.isEmpty() ? PythonValues.none() : PythonValues.max(errors));
	}

}
