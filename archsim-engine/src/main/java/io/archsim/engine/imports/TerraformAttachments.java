package io.archsim.engine.imports;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.imports.InstanceSizes.Size;
import io.archsim.engine.imports.TerraformParts.Cloud;
import io.archsim.engine.imports.TerraformParts.Located;

/**
 * The second pass over a Terraform plan's resources: those that attach to parts made in
 * the first. A read replica counts on its source, an Aurora instance on its cluster (the
 * first one sizes it; more than one are a writer and readers), and a node pool sizes and
 * counts its cluster's nodes.
 */
final class TerraformAttachments {

	private static final List<String> NODE_SIZE = List.of("nodeVcpu", "nodeMemoryGib");

	private final TerraformParts parts;

	private final DesignBuilder design;

	private final ImportLog log;

	/** The instances counted on each Aurora cluster, by part. */
	private final Map<String, Integer> instances = new LinkedHashMap<>();

	/**
	 * Attaches resources to the parts made so far.
	 * @param parts the parts made in the first pass
	 */
	TerraformAttachments(TerraformParts parts) {
		this.parts = parts;
		this.design = parts.design();
		this.log = parts.log();
	}

	/**
	 * Attaches each resource that attaches to another's part, then reads each Aurora
	 * cluster's instances as a writer and readers.
	 * @param resources the plan's resources, in order
	 */
	void attach(List<TerraformResource> resources) {
		for (TerraformResource resource : resources) {
			if (resource.is("aws_db_instance")) {
				JsonNode source = resource.values().path("replicate_source_db");
				if (PythonValues.truthy(source)) {
					replica(resource, source);
				}
			}
			else if (resource.is("aws_rds_cluster_instance")) {
				clusterInstance(resource);
			}
			else if (resource.is("aws_eks_node_group") || resource.is("google_container_node_pool")) {
				nodePool(resource);
			}
		}
		this.design.nodes().forEach(this::guessReaders);
	}

	/**
	 * A read replica, counted on its source: the part whose address ends with the
	 * source's name, or whose label is it.
	 * @param resource the replica
	 * @param source its source, as written
	 */
	private void replica(TerraformResource resource, JsonNode source) {
		String suffix = PythonValues.str(source);
		Optional<String> found = Optional.empty();
		for (Located located : this.parts.located()) {
			if (endsWith(located.address(), suffix) || isLabelled(located.partId(), source)) {
				found = Optional.of(located.partId());
				break;
			}
		}
		String address = PythonValues.str(resource.address());
		if (found.isEmpty()) {
			this.log.ignored(address + " (a read replica of '" + suffix + "', which is not in the plan)");
			return;
		}
		ObjectNode config = config(found.get());
		JsonNode replicas = config.path("readReplicas");
		BigInteger counted = replicas.isMissingNode() ? BigInteger.ZERO : PythonValues.toInt(replicas);
		config.set("readReplicas", PythonValues.number(counted.add(BigInteger.ONE)));
		this.log.mapped(address + " -> a read replica of " + found.get());
	}

	/**
	 * An Aurora instance, counted on its cluster, whose size the first one gives.
	 * @param resource the instance
	 */
	private void clusterInstance(TerraformResource resource) {
		ObjectNode values = resource.values();
		Optional<String> cluster = this.parts.cluster(PythonValues.str(values.path("cluster_identifier")));
		if (cluster.isEmpty()) {
			this.log.ignored(PythonValues.str(resource.address()) + " (its cluster is not in the plan)");
			return;
		}
		String partId = cluster.get();
		this.instances.merge(partId, 1, Integer::sum);
		ObjectNode config = config(partId);
		if (!config.has("cpuCores")) {
			JsonNode instanceClass = values.path("instance_class");
			InstanceSizes.aws(instanceClass).ifPresent((size) -> {
				config.set("cpuCores", size.cpu());
				config.set("memoryGib", size.memory());
				this.design.note(partId, "cpuCores", Cloud.AWS.derivation(instanceClass));
				this.design.note(partId, "memoryGib", Cloud.AWS.derivation(instanceClass));
			});
		}
		this.log.mapped(PythonValues.str(resource.address()) + " -> an instance of " + partId);
	}

	/**
	 * A node pool: its cluster's node count, bounds and node size. Settings the pool
	 * leaves unset are left out, also when an earlier pool set them.
	 * @param resource the pool
	 */
	private void nodePool(TerraformResource resource) {
		ObjectNode values = resource.values();
		JsonNode named = values.path("cluster_name");
		Optional<String> cluster = this.parts
			.cluster(PythonValues.str(PythonValues.truthy(named) ? named : values.path("cluster")));
		if (cluster.isEmpty()) {
			this.log.ignored(PythonValues.str(resource.address()) + " (its cluster is not in the plan)");
			return;
		}
		ObjectNode config = config(cluster.get());
		Optional<Size> size = resource.is("aws_eks_node_group") ? eksNodes(values, config) : gkeNodes(values, config);
		size.ifPresent((nodeSize) -> {
			config.set("nodeVcpu", nodeSize.cpu());
			config.set("nodeMemoryGib", nodeSize.memory());
			for (String key : NODE_SIZE) {
				this.design.note(cluster.get(), key, "from the node pool's machine type");
			}
		});
		List<String> unset = new ArrayList<>();
		config.properties().forEach((setting) -> {
			if (setting.getValue().isNull()) {
				unset.add(setting.getKey());
			}
		});
		unset.forEach(config::remove);
		this.log.mapped(PythonValues.str(resource.address()) + " -> the node pool of " + cluster.get());
	}

	/**
	 * An EKS node group's scaling, always autoscaled.
	 * @param values the group's values
	 * @param config its cluster's settings
	 * @return the size of its first instance type
	 */
	private static Optional<Size> eksNodes(ObjectNode values, ObjectNode config) {
		ObjectNode scaling = TerraformResource.first(values.path("scaling_config"));
		JsonNode types = values.path("instance_types");
		Optional<Size> size = InstanceSizes.aws(PythonValues.truthy(types) ? PythonValues.first(types) : types);
		config.set("nodes", setting(scaling.path("desired_size")));
		config.set("minNodes", setting(scaling.path("min_size")));
		config.set("maxNodes", setting(scaling.path("max_size")));
		config.put("clusterAutoscaler", true);
		return size;
	}

	/**
	 * A GKE node pool's count and autoscaling bounds.
	 * @param values the pool's values
	 * @param config its cluster's settings
	 * @return the size of its machine type
	 */
	private static Optional<Size> gkeNodes(ObjectNode values, ObjectNode config) {
		ObjectNode autoscaling = TerraformResource.first(values.path("autoscaling"));
		Optional<Size> size = InstanceSizes
			.gcp(TerraformResource.first(values.path("node_config")).path("machine_type"));
		JsonNode count = values.path("node_count");
		config.set("nodes", setting(PythonValues.truthy(count) ? count : values.path("initial_node_count")));
		config.set("minNodes", setting(autoscaling.path("min_node_count")));
		config.set("maxNodes", setting(autoscaling.path("max_node_count")));
		return size;
	}

	/**
	 * Reads an Aurora cluster's instances beyond one as readers (a guess).
	 * @param node the part
	 */
	private void guessReaders(ObjectNode node) {
		String nodeId = node.path("id").stringValue();
		int counted = this.instances.getOrDefault(nodeId, 0);
		if (counted <= 1) {
			return;
		}
		((ObjectNode) node.path("config")).put("readReplicas", counted - 1);
		this.design.note(nodeId, "readReplicas", "guessed: " + counted + " cluster instances as a writer and readers");
		this.log
			.guessed(nodeId + ": " + counted + " cluster instances read as a writer and " + (counted - 1) + " readers");
	}

	private ObjectNode config(String partId) {
		return (ObjectNode) this.design.node(partId).orElseThrow().path("config");
	}

	private boolean isLabelled(String partId, JsonNode source) {
		JsonNode label = this.design.node(partId).orElseThrow().path("label");
		return source.isString() && label.isString() && source.stringValue().equals(label.stringValue());
	}

	/**
	 * {@code address.endswith(suffix)}.
	 * @param address an address, as written
	 * @param suffix the suffix
	 * @return whether it ends with the suffix
	 * @throws ImportShapeException in Python's words for an address that is not text
	 */
	private static boolean endsWith(JsonNode address, String suffix) {
		if (!address.isString()) {
			throw new ImportShapeException("'" + PythonValues.type(address) + "' object has no attribute 'endswith'");
		}
		return address.stringValue().endsWith(suffix);
	}

	/**
	 * A setting as Python's {@code dict.update} sets it: a missing value is unset.
	 * @param value the value, as written
	 * @return the value; a null node for a missing one
	 */
	private static JsonNode setting(JsonNode value) {
		return value.isMissingNode() ? JsonNodeFactory.instance.nullNode() : value;
	}

}
