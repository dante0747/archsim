package io.archsim.engine.imports;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.engine.imports.InstanceSizes.Size;

/**
 * The parts a Terraform plan's resources become, as the resources are read: each added to
 * the design and the report, by the resource's address, and the clusters other resources
 * attach to, by name.
 */
final class TerraformParts {

	/** The values a part is named by, the first that counts as true. */
	private static final List<String> NAME_FIELDS = List.of("name", "identifier", "cluster_id", "function_name");

	private final DesignBuilder design;

	private final ImportLog log;

	private final Map<String, Located> byAddress = new LinkedHashMap<>();

	private final Map<String, String> clusters = new LinkedHashMap<>();

	/**
	 * Parts added to a design.
	 * @param design the design
	 * @param log the report
	 */
	TerraformParts(DesignBuilder design, ImportLog log) {
		this.design = design;
		this.log = log;
	}

	DesignBuilder design() {
		return this.design;
	}

	ImportLog log() {
		return this.log;
	}

	/**
	 * Adds the part a resource becomes, named by its values' name (or identifier, cluster
	 * id, function name) else the resource's, and lists it as mapped.
	 * @param resource the resource
	 * @param type the part type
	 * @param config its settings, unset ones left out
	 * @param what what the resource is, for the report; empty for nothing
	 * @return the part's id
	 */
	String add(TerraformResource resource, String type, TerraformConfig config, String what) {
		String base = PythonValues.str(name(resource.values(), resource));
		String nodeId = this.design.uniqueId(base);
		String label = config.label();
		this.design.add(nodeId, type, (label != null) ? label : base, config.written());
		config.notes().forEach((key, text) -> this.design.note(nodeId, key, text));
		JsonNode address = resource.address();
		// a dict keeps the key it was first given, with the last value
		this.byAddress.merge(PythonValues.key(address), new Located(address, nodeId),
				(first, last) -> new Located(first.address(), last.partId()));
		String displayName = this.design.registry().find(type).orElseThrow().displayName();
		this.log.mapped(PythonValues.str(address) + " -> " + displayName + (what.isEmpty() ? "" : " (" + what + ")"));
		return nodeId;
	}

	/**
	 * Adds the Kubernetes cluster a resource becomes, named by its values' name (else the
	 * resource's) for the node pools that attach to it.
	 * @param resource the resource
	 * @param config its settings, unset ones left out
	 * @param what what the resource is, for the report
	 */
	void addCluster(TerraformResource resource, TerraformConfig config, String what) {
		String partId = add(resource, "kubernetes_cluster", config, what);
		JsonNode name = resource.values().path("name");
		cluster(PythonValues.str(PythonValues.truthy(name) ? name : resource.requiredName()), partId);
	}

	/**
	 * Sizes a part by its instance type, or lists the type as one to size by hand.
	 * @param config the part's settings
	 * @param instance the type, as written
	 * @param cloud the cloud whose type it is
	 * @throws ImportShapeException in Python's words for a type that is not text
	 */
	void sized(TerraformConfig config, JsonNode instance, Cloud cloud) {
		Optional<Size> size = cloud.size(instance);
		if (size.isEmpty()) {
			if (PythonValues.truthy(instance)) {
				this.log.guessed("instance type '" + PythonValues.str(instance) + "' is not in the size table ("
						+ cloud.docs() + "): set CPU and memory yourself");
			}
			return;
		}
		config.put("cpuCores", size.get().cpu()).put("memoryGib", size.get().memory());
		config.derived(cloud.derivation(instance), "cpuCores", "memoryGib");
		if (cloud == Cloud.AWS && PythonValues.truthy(instance)) {
			String type = instance.stringValue();
			boolean kafka = config.get("technology").isString()
					&& "kafka".equals(config.get("technology").stringValue());
			// priced as that type (MSK's broker types carry a "kafka." prefix in the
			// snapshot)
			config.put("instanceType", (kafka && !type.startsWith("kafka.")) ? "kafka." + type : type);
		}
	}

	/**
	 * Lists a resource the import does not read as ignored.
	 * @param resource the resource
	 */
	void ignore(TerraformResource resource) {
		this.log.ignored(PythonValues.str(resource.address()) + " (" + PythonValues.str(resource.type()) + ")");
	}

	/**
	 * Names a cluster other resources attach to.
	 * @param name its name, as they name it
	 * @param partId its part
	 */
	void cluster(String name, String partId) {
		this.clusters.put(name, partId);
	}

	/**
	 * The cluster a name names.
	 * @param name the name
	 * @return its part; empty when none has it
	 */
	Optional<String> cluster(String name) {
		return Optional.ofNullable(this.clusters.get(name));
	}

	/**
	 * The parts added, by their resources' addresses.
	 * @return each resource's address, as written, and its part, in the order first added
	 */
	Collection<Located> located() {
		return Collections.unmodifiableCollection(this.byAddress.values());
	}

	/**
	 * The name of a resource's part: its values' {@code name}, {@code identifier},
	 * {@code cluster_id} or {@code function_name}, else the resource's name.
	 * @param values the resource's values
	 * @param resource the resource
	 * @return the first that counts as true, else the resource's name, as written
	 */
	private static JsonNode name(JsonNode values, TerraformResource resource) {
		for (String field : NAME_FIELDS) {
			if (PythonValues.truthy(values.path(field))) {
				return values.path(field);
			}
		}
		JsonNode name = resource.name();
		return name.isMissingNode() ? JsonNodeFactory.instance.nullNode() : name;
	}

	/**
	 * A part, with its resource's address as the plan wrote it.
	 *
	 * @param address the address
	 * @param partId the part's id
	 */
	record Located(JsonNode address, String partId) {

	}

	/**
	 * A cloud: how its instance types are sized, and where they are documented.
	 */
	enum Cloud {

		/** Amazon Web Services. */
		AWS("https://aws.amazon.com/ec2/instance-types/"),

		/** Google Cloud. */
		GCP("https://cloud.google.com/compute/docs/general-purpose-machines"),

		/** Microsoft Azure. */
		AZURE("https://learn.microsoft.com/en-us/azure/virtual-machines/sizes");

		private final String docs;

		Cloud(String docs) {
			this.docs = docs;
		}

		String docs() {
			return this.docs;
		}

		/**
		 * The size of one of the cloud's instance types.
		 * @param instance the type, as written
		 * @return its vCPUs and GiB; empty for none, or one the tables do not hold
		 * @throws ImportShapeException in Python's words for a type that is not text
		 */
		Optional<Size> size(JsonNode instance) {
			return switch (this) {
				case AWS -> InstanceSizes.aws(instance);
				case GCP -> InstanceSizes.gcp(instance);
				case AZURE -> InstanceSizes.azure(instance);
			};
		}

		/**
		 * How a number from one of the cloud's instance types was derived.
		 * @param instance the type, as written
		 * @return {@code from instance type <type> (<documentation>)}
		 */
		String derivation(JsonNode instance) {
			return "from instance type " + PythonValues.str(instance) + " (" + this.docs + ")";
		}

	}

}
