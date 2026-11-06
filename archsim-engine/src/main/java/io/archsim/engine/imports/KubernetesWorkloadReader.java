package io.archsim.engine.imports;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.imports.ImageKind.Use;
import io.archsim.engine.imports.Manifests.Workload;
import io.archsim.engine.roundtrip.RoundTrip;
import io.archsim.model.text.PythonFormat;

/**
 * One Kubernetes workload read into a part: what it becomes by its image, its requests,
 * replicas, storage and annotations, and what the report says of it.
 */
final class KubernetesWorkloadReader {

	private static final String SERVICE = "service";

	private static final String DATABASE = "database";

	private static final String MESSAGE_QUEUE = "message_queue";

	private final DesignBuilder design;

	private final Workload workload;

	private final Map<String, String> annotations;

	private final ExportLabels exportLabels;

	private final ObjectNode spec;

	private final boolean knative;

	private final ObjectNode container;

	private final String image;

	private final ImagePart part;

	private final String nodeId;

	private final ObjectNode config = JsonNodeFactory.instance.objectNode();

	private final List<String> settings = new ArrayList<>();

	/**
	 * The guesses made while reading, which the report lists before how the part was
	 * read.
	 */
	private final List<String> guesses = new ArrayList<>();

	/**
	 * Reads a workload: its annotations, its first container's image and the part id it
	 * gets, in the order Python reads them.
	 * @param design the design, which gives the part id and gets the part
	 * @param workload the workload
	 */
	KubernetesWorkloadReader(DesignBuilder design, Workload workload) {
		this.design = design;
		this.workload = workload;
		ObjectNode object = workload.object();
		this.annotations = PythonMappings
			.texts(PythonMappings.dict(object.path("metadata"), "get").path("annotations"));
		this.exportLabels = new ExportLabels(this.annotations);
		this.spec = PythonMappings.dict(object.path("spec"), "get");
		this.knative = (object.has("apiVersion") ? PythonValues.str(object.get("apiVersion")) : "")
			.startsWith("serving.knative.dev");
		this.container = workload.container();
		this.image = PythonValues.strOrEmpty(this.container.path("image"));
		this.part = ImagePart.of(design.registry(), this.image, this.exportLabels);
		String stated = this.annotations.getOrDefault("archsim.dev/node", "");
		this.nodeId = design.uniqueId(stated.isEmpty() ? PythonValues.matchedText(workload.nameNode()) : stated);
	}

	String nodeId() {
		return this.nodeId;
	}

	/**
	 * The links the workload states ({@code archsim.dev/calls}).
	 * @return them, as an export writes them; empty when it states none
	 */
	Optional<String> statedCalls() {
		return Optional.ofNullable(this.annotations.get("archsim.dev/calls"));
	}

	/**
	 * Whether the replicas are the ones Kubernetes runs by default: not a Knative
	 * Service's or a DaemonSet's, and no {@code spec.replicas} written.
	 * @return {@code true} when they are
	 */
	boolean replicasUnset() {
		return !this.knative && !isDaemonSet() && !PythonValues.isInt(this.spec.path("replicas"));
	}

	boolean isTool() {
		return this.part.image() != null && this.part.image().use() == Use.TOOL;
	}

	/**
	 * Whether the part is a service on Kubernetes, which a cluster part holds: not
	 * serverless, and not one an export places on its own platform.
	 * @return {@code true} for such a service
	 */
	boolean runsOnKubernetes() {
		return SERVICE.equals(this.part.type()) && !this.knative
				&& this.annotations.getOrDefault("archsim.dev/platform", "").isEmpty();
	}

	/**
	 * Adds the workload's part to the design, once, and what the report says of it.
	 * @param log the report
	 */
	void addTo(ImportLog log) {
		config();
		report(log);
		this.design.add(this.nodeId, this.part.type(), this.workload.nameNode(), this.config);
	}

	/**
	 * Sets the part's settings: its technology and platform, requests, replicas, storage
	 * and what its annotations state.
	 */
	private void config() {
		String technology = this.part.technology();
		if (technology != null) {
			this.config.put("technology", technology);
		}
		if (SERVICE.equals(this.part.type())) {
			this.config.put("platform", this.knative ? "serverless" : "kubernetes");
		}
		requests();
		JsonNode replicas = replicas();
		storage();
		List<String> read = this.exportLabels.applyTo(this.config, this.part.type());
		if (!read.isEmpty()) {
			this.settings.add(String.join(", ", read) + " from its annotations");
		}
		if (!read.contains("topology")) {
			readReplicas(replicas);
		}
	}

	/**
	 * Writes what the report says of the workload: mapped when an ArchSim export or the
	 * manifest states it, else a guess from its image, with why; and the guesses made.
	 * @param log the report
	 */
	private void report(ImportLog log) {
		this.guesses.forEach(log::guessed);
		String named = PythonValues.str(this.workload.object().path("kind")) + " " + this.workload.name();
		ImageKind kind = this.part.image();
		if (this.annotations.containsKey("archsim.dev/node") || kind == null || this.image.startsWith("your-org/")) {
			String what = this.image.isEmpty() ? "no image" : this.image;
			log.mapped(named + ": " + what + " -> " + this.part.displayName()
					+ (this.settings.isEmpty() ? "" : "; " + String.join("; ", this.settings)));
			return;
		}
		if (kind.use() == Use.INFRASTRUCTURE) {
			log.guessed(named + ": " + this.part.displayName() + ", because '" + this.image + "' " + kind.why());
		}
		else {
			log.guessed(named + ": '" + this.image + "' " + kind.why() + ", so it became a service");
		}
		if (!this.settings.isEmpty()) {
			log.mapped(named + ": " + String.join("; ", this.settings));
		}
	}

	private void requests() {
		ObjectNode resources = PythonMappings.dict(this.container.path("resources"), "get");
		JsonNode requests = PythonValues.truthy(resources.path("requests")) ? resources.path("requests")
				: resources.path("limits");
		ObjectNode quantities = PythonMappings.dict(requests, "get");
		double cpu = Quantities.cpu(quantities.path("cpu")).orElse(0);
		double memory = Quantities.memoryGib(quantities.path("memory")).orElse(0);
		// ArchSim's exporter writes these requests for a part with no CPU or memory set;
		// only its own manifests carry them as a placeholder
		if (this.annotations.containsKey("archsim.dev/node") && SERVICE.equals(this.part.type())
				&& resources.equals(RoundTrip.placeholderResources())) {
			return;
		}
		if (cpu != 0) {
			this.config.put("cpuCores", cpu);
			this.design.note(this.nodeId, "cpuCores", "container resources");
			this.settings.add(PythonFormat.format(cpu, "g") + " CPU");
		}
		if (memory != 0) {
			this.config.put("memoryGib", memory);
			this.design.note(this.nodeId, "memoryGib", "container resources");
			this.settings.add(PythonFormat.format(memory, "g") + " GiB memory");
		}
	}

	/**
	 * The workload's replicas: a Knative Service's scale annotations, a DaemonSet's left
	 * at ArchSim's default, any other's {@code spec.replicas} (one pod when unset).
	 * @return the replicas the workload is read with, for a database's read replicas
	 */
	private JsonNode replicas() {
		JsonNode replicas = this.spec.path("replicas");
		if (this.knative) {
			new KnativeScale(this.design, this.nodeId).read(PythonMappings.dict(this.spec.path("template"), "get"),
					this.config);
			return replicas;
		}
		if (isDaemonSet()) {
			if (SERVICE.equals(this.part.type())) {
				this.guesses.add("DaemonSet " + this.workload.name() + ": one pod per node, so its replicas are left "
						+ "at ArchSim's default; set them to the node count");
			}
			return replicas;
		}
		if (replicasUnset()) {
			JsonNode one = PythonValues.number(BigInteger.ONE);
			replicasAs(one, "not set: Kubernetes runs 1 pod");
			return one;
		}
		replicasAs(replicas, "spec.replicas");
		String count = PythonValues.str(replicas);
		if (SERVICE.equals(this.part.type())) {
			this.settings.add("replicas " + count);
		}
		else if (MESSAGE_QUEUE.equals(this.part.type())) {
			this.settings.add(count + " brokers");
		}
		return replicas;
	}

	/**
	 * Sets a service's replicas, or a queue's brokers; a part of another type has
	 * neither.
	 * @param count how many
	 * @param source where the number came from
	 */
	private void replicasAs(JsonNode count, String source) {
		if (SERVICE.equals(this.part.type())) {
			this.config.set("replicas", count);
			this.design.note(this.nodeId, "replicas", source);
		}
		else if (MESSAGE_QUEUE.equals(this.part.type())) {
			this.config.set("brokers", count);
			this.design.note(this.nodeId, "brokers", "StatefulSet replicas (" + source + ")");
		}
	}

	private boolean isDaemonSet() {
		return "DaemonSet".equals(PythonValues.str(this.workload.object().path("kind")));
	}

	private void storage() {
		JsonNode claims = this.spec.path("volumeClaimTemplates");
		if (!PythonValues.truthy(claims)) {
			return;
		}
		ObjectNode claim = PythonMappings.mapping(PythonValues.first(claims));
		ObjectNode requests = PythonMappings
			.dict(PythonMappings.dict(PythonMappings.dict(claim.path("spec"), "get").path("resources"), "get")
				.path("requests"), "get");
		double storage = Quantities.memoryGib(requests.path("storage")).orElse(0);
		if (storage != 0 && (DATABASE.equals(this.part.type()) || MESSAGE_QUEUE.equals(this.part.type()))) {
			this.config.put("storageGib", storage);
			this.design.note(this.nodeId, "storageGib", "volumeClaimTemplates storage request");
			this.settings.add(PythonFormat.format(storage, "g") + " GiB storage");
		}
	}

	/**
	 * A database's pods beyond one as read replicas (a guess).
	 * @param replicas the replicas the workload is read with
	 */
	private void readReplicas(JsonNode replicas) {
		if (!DATABASE.equals(this.part.type()) || !PythonValues.isInt(replicas)) {
			return;
		}
		BigInteger pods = PythonValues.toInt(replicas);
		if (pods.compareTo(BigInteger.ONE) <= 0) {
			return;
		}
		BigInteger readReplicas = pods.subtract(BigInteger.ONE);
		this.config.set("readReplicas", PythonValues.number(readReplicas));
		this.design.note(this.nodeId, "readReplicas", "guessed: " + pods + " pods as a primary and read replicas");
		this.guesses
			.add(this.workload.name() + ": " + pods + " pods read as a primary and " + readReplicas + " read replicas");
	}

}
