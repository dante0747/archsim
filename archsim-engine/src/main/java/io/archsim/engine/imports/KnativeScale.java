package io.archsim.engine.imports;

import java.math.BigInteger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Knative Service's scale, from its template's annotations: its most instances (as a
 * concurrency limit), its first instances (as replicas) and its requests per instance.
 */
final class KnativeScale {

	private final DesignBuilder design;

	private final String nodeId;

	/**
	 * Reads a part's scale.
	 * @param design the design, which notes where the replicas came from
	 * @param nodeId the part
	 */
	KnativeScale(DesignBuilder design, String nodeId) {
		this.design = design;
		this.nodeId = nodeId;
	}

	/**
	 * Reads the scale into the part's config.
	 * @param template the Service's template
	 * @param config the part's config
	 */
	void read(ObjectNode template, ObjectNode config) {
		ObjectNode scaling = PythonMappings
			.dict(PythonMappings.dict(template.path("metadata"), "get").path("annotations"), "get");
		JsonNode maxScale = scaling.path("autoscaling.knative.dev/max-scale");
		if (PythonValues.truthy(maxScale)) {
			config.set("concurrencyLimit", PythonValues.number(truncated(maxScale)));
		}
		JsonNode initial = scaling.path("autoscaling.knative.dev/initial-scale");
		if (PythonValues.truthy(initial)) {
			config.set("replicas", PythonValues.number(truncated(initial).max(BigInteger.ONE)));
			this.design.note(this.nodeId, "replicas", "autoscaling.knative.dev/initial-scale");
		}
		JsonNode concurrency = PythonMappings.dict(template.path("spec"), "get").path("containerConcurrency");
		if (PythonValues.isInt(concurrency) && PythonValues.toInt(concurrency).signum() > 0) {
			config.set("workersPerReplica", concurrency);
		}
	}

	/**
	 * {@code int(float(value))}.
	 * @param value the value
	 * @return its whole number, a fraction truncated
	 */
	private static BigInteger truncated(JsonNode value) {
		return PythonValues.toInt(JsonNodeFactory.instance.numberNode(PythonValues.toFloat(value)));
	}

}
