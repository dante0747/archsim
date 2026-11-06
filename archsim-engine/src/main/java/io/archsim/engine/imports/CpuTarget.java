package io.archsim.engine.imports;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An autoscaler's CPU utilization target (autoscaling/v1, v2beta1 or v2), how it was
 * found, and the other metrics it names (memory, custom metrics: ArchSim scales on CPU
 * only).
 *
 * @param utilization the target, as a fraction; {@code null} for none
 * @param why how it was found
 * @param others the other metrics it names
 */
record CpuTarget(@Nullable Double utilization, String why, List<String> others) {

	/** Kubernetes' CPU target when an autoscaler names no metric. */
	private static final double DEFAULT_TARGET = 0.8;

	/**
	 * Copies the metrics, so the record does not change.
	 */
	CpuTarget {
		others = List.copyOf(others);
	}

	/**
	 * The target of an autoscaler.
	 * @param spec the autoscaler's spec
	 * @return its target: its v1 percentage, else its last CPU utilization metric, else
	 * Kubernetes' default when it names no metric at all
	 */
	static CpuTarget of(ObjectNode spec) {
		Double utilization = null;
		String why = "HorizontalPodAutoscaler";
		JsonNode percentage = spec.path("targetCPUUtilizationPercentage");
		if (percentage.isNumber()) {
			utilization = PythonValues.percent(percentage);
			why = "HorizontalPodAutoscaler targetCPUUtilizationPercentage";
		}
		List<String> others = new ArrayList<>();
		List<JsonNode> metrics = PythonValues.items(spec.path("metrics")).stream().filter(JsonNode::isObject).toList();
		for (JsonNode metric : metrics) {
			Double measured = utilization(metric, others);
			if (measured != null) {
				utilization = measured;
				why = "HorizontalPodAutoscaler CPU metric";
			}
		}
		if (utilization == null && (percentage.isMissingNode() || percentage.isNull()) && metrics.isEmpty()) {
			return new CpuTarget(DEFAULT_TARGET, "not set: Kubernetes' default of 80% CPU", others);
		}
		return new CpuTarget(utilization, why, others);
	}

	/**
	 * A metric's CPU utilization target, or what else it measures.
	 * @param metric the metric
	 * @param others the other metrics, which gets this one when it is not a CPU
	 * utilization
	 * @return its utilization target, as a fraction; {@code null} for none
	 */
	private static @Nullable Double utilization(JsonNode metric, List<String> others) {
		JsonNode resource = PythonValues.truthy(metric.path("resource")) ? metric.path("resource")
				: metric.path("containerResource");
		ObjectNode measured = PythonMappings.dict(resource, "get");
		String type = metric.path("type").asString("");
		boolean cpu = ("Resource".equals(type) || "ContainerResource".equals(type))
				&& "cpu".equals(measured.path("name").asString(null));
		if (!cpu) {
			others.add(name(measured.path("name"), metric.path("type")));
			return null;
		}
		JsonNode goal = PythonMappings.dict(measured.path("target"), "get").path("averageUtilization");
		JsonNode average = PythonValues.truthy(goal) ? goal : measured.path("targetAverageUtilization");
		if (average.isNumber()) {
			return PythonValues.percent(average);
		}
		others.add("cpu (an average value, not a utilization)");
		return null;
	}

	/** {@code str(name or type or "unnamed")}. */
	private static String name(JsonNode name, JsonNode type) {
		if (PythonValues.truthy(name)) {
			return PythonValues.str(name);
		}
		return PythonValues.truthy(type) ? PythonValues.str(type) : "unnamed";
	}

}
