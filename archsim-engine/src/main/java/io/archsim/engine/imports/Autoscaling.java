package io.archsim.engine.imports;

import java.math.BigInteger;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A HorizontalPodAutoscaler read into its workload's settings: its least and most
 * replicas, its CPU utilization target (autoscaling/v1, v2beta1 or v2), and its scale-up
 * period; any other metric, a CPU target that is not a utilization included, is listed as
 * ignored, as ArchSim's autoscaler reads only worker and CPU utilization.
 */
final class Autoscaling {

	/** The least and most CPU utilization targets taken. */
	private static final double LEAST_TARGET = 0.1;

	private static final double MOST_TARGET = 0.95;

	/**
	 * What the autoscaler holds to its target, so the report ignores any other measure.
	 */
	private static final String WHAT_IT_SCALES_ON = "ArchSim scales on worker utilization "
			+ "or, with a CPU size, CPU utilization if higher";

	private final DesignBuilder design;

	private final ImportLog log;

	private final PartNames names;

	private final Set<String> unsetReplicas;

	/**
	 * Reads autoscalers into a design.
	 * @param design the design, whose parts they scale
	 * @param log the report
	 * @param names the parts by workload name, which the autoscalers name
	 * @param unsetReplicas the parts whose replicas Kubernetes' default gives, which an
	 * autoscaler sets
	 */
	Autoscaling(DesignBuilder design, ImportLog log, PartNames names, Set<String> unsetReplicas) {
		this.design = design;
		this.log = log;
		this.names = names;
		this.unsetReplicas = unsetReplicas;
	}

	/**
	 * Reads an autoscaler.
	 * @param autoscaler the autoscaler
	 */
	void read(ObjectNode autoscaler) {
		JsonNode name = PythonMappings.dict(autoscaler.path("metadata"), "get").path("name");
		String named = "HorizontalPodAutoscaler " + (name.isMissingNode() ? "" : PythonValues.str(name));
		ObjectNode spec = PythonMappings.dict(autoscaler.path("spec"), "get");
		JsonNode reference = PythonMappings.dict(spec.path("scaleTargetRef"), "get").path("name");
		Optional<ObjectNode> node = this.design.node(this.names.partId(PythonValues.str(reference)).orElse(""));
		if (node.isEmpty()) {
			this.log.ignored(named + " (no matching workload)");
			return;
		}
		String nodeId = node.get().path("id").stringValue();
		ObjectNode config = (ObjectNode) node.get().path("config");
		bounds(node.get(), spec, config);
		target(named, nodeId, spec, config);
		JsonNode policies = PythonMappings
			.dict(PythonMappings.dict(spec.path("behavior"), "get").path("scaleUp"), "get")
			.path("policies");
		if (PythonValues.truthy(policies)) {
			JsonNode period = PythonMappings.mapping(PythonValues.first(policies)).path("periodSeconds");
			if (PythonValues.truthy(period)) {
				config.set("scaleIntervalS", period);
			}
		}
		this.log.mapped(named + " -> autoscaling on " + nodeId);
	}

	/**
	 * The least and most replicas, and the replicas themselves when Kubernetes' default
	 * gave them.
	 * @param node the workload's part
	 * @param spec the autoscaler's spec
	 * @param config the part's config
	 */
	private void bounds(ObjectNode node, ObjectNode spec, ObjectNode config) {
		String nodeId = node.path("id").stringValue();
		config.put("autoscaling", true);
		config.set("minReplicas", PythonValues.number(PythonValues.toInt(orOne(spec.path("minReplicas")))));
		config.set("maxReplicas", PythonValues.number(PythonValues.toInt(orOne(spec.path("maxReplicas")))));
		this.design.note(nodeId, "minReplicas", "HorizontalPodAutoscaler");
		this.design.note(nodeId, "maxReplicas", "HorizontalPodAutoscaler");
		if (this.unsetReplicas.contains(nodeId) && "service".equals(node.path("type").stringValue())) {
			config.set("replicas", config.get("minReplicas"));
			this.design.note(nodeId, "replicas", "not set: the HorizontalPodAutoscaler's minReplicas");
		}
	}

	/**
	 * The CPU utilization target, within bounds, and the other metrics listed as ignored.
	 * @param named the autoscaler, as the report names it
	 * @param nodeId the workload's part
	 * @param spec the autoscaler's spec
	 * @param config the part's config
	 */
	private void target(String named, String nodeId, ObjectNode spec, ObjectNode config) {
		CpuTarget target = CpuTarget.of(spec);
		Double utilization = target.utilization();
		if (utilization != null) {
			config.put("targetUtilization", PythonValues.min(MOST_TARGET, PythonValues.max(LEAST_TARGET, utilization)));
			this.design.note(nodeId, "targetUtilization", target.why());
		}
		else {
			this.log
				.guessed(named + " has no CPU utilization target: " + nodeId + " scales at ArchSim's default target");
		}
		if (!target.others().isEmpty()) {
			this.log.ignored(named + ": " + String.join(", ", target.others()) + " metric"
					+ ((target.others().size() > 1) ? "s" : "") + " (" + WHAT_IT_SCALES_ON + ")");
		}
	}

	private static JsonNode orOne(JsonNode value) {
		return PythonValues.truthy(value) ? value : PythonValues.number(BigInteger.ONE);
	}

}
