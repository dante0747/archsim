package io.archsim.engine.imports;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.imports.ImageKind.Use;
import io.archsim.model.text.PythonFormat;

/**
 * One compose service read into a part: what it becomes by its image, its replicas,
 * specs, connection limit and labels, and what the report says of it.
 */
final class ComposeServiceReader {

	private static final String SERVICE = "service";

	/** The keys of a service the import does not read, listed as ignored. */
	private static final Set<String> IGNORED = Set.of("volumes", "networks", "healthcheck", "restart", "build",
			"container_name", "env_file", "logging", "secrets", "configs");

	private static final Pattern CONNECTION_LIMIT = Pattern.compile("max_connections=(\\d+)|--max-connections=(\\d+)",
			Pattern.UNICODE_CHARACTER_CLASS);

	private final DesignBuilder design;

	private final String serviceName;

	private final ObjectNode service;

	private final Map<String, String> labels;

	private final ExportLabels exportLabels;

	private final String image;

	private final ImagePart part;

	private final String nodeId;

	private final ObjectNode deploy;

	private final ObjectNode config = JsonNodeFactory.instance.objectNode();

	private final List<String> settings = new ArrayList<>();

	/**
	 * Reads a service: its labels, its image, the part id it gets and its deploy
	 * settings, in the order Python reads them.
	 * @param design the design, which gives the part id and gets the part
	 * @param serviceName the service's name
	 * @param service its settings
	 */
	ComposeServiceReader(DesignBuilder design, String serviceName, ObjectNode service) {
		this.design = design;
		this.serviceName = serviceName;
		this.service = service;
		this.labels = ComposeSettings.labels(service);
		this.exportLabels = new ExportLabels(this.labels);
		this.image = PythonValues.strOrEmpty(service.path("image"));
		this.part = ImagePart.of(design.registry(), this.image, this.exportLabels);
		String stated = this.labels.get("archsim.node");
		this.nodeId = design.uniqueId((stated == null || stated.isEmpty()) ? serviceName : stated);
		this.deploy = PythonMappings.dict(service.path("deploy"), "get");
	}

	String nodeId() {
		return this.nodeId;
	}

	/**
	 * The links the service states ({@code archsim.calls}).
	 * @return them, as an export writes them; empty when it states none
	 */
	Optional<String> statedCalls() {
		return Optional.ofNullable(this.labels.get("archsim.calls"));
	}

	boolean isTool() {
		return this.part.image() != null && this.part.image().use() == Use.TOOL;
	}

	boolean publishesPorts() {
		return PythonValues.truthy(this.service.path("ports"));
	}

	/**
	 * Adds the service's part to the design, once, and what the report says of it.
	 * @param log the report
	 */
	void addTo(ImportLog log) {
		config();
		report(log);
		this.design.add(this.nodeId, this.part.type(), this.serviceName, this.config);
	}

	/**
	 * Sets the part's settings: its technology, replicas, specs, connection limit and
	 * what its labels state.
	 */
	private void config() {
		String technology = this.part.technology();
		if (technology != null) {
			this.config.put("technology", technology);
		}
		if (SERVICE.equals(this.part.type())) {
			replicas();
		}
		specs();
		connectionLimit();
		List<String> read = this.exportLabels.applyTo(this.config, this.part.type());
		if (!read.isEmpty()) {
			this.settings.add(String.join(", ", read) + " from its labels");
		}
	}

	/**
	 * Writes what the report says of the service: mapped when an ArchSim export or the
	 * file states it, else a guess from its image, with why; and what it ignored.
	 * @param log the report
	 */
	private void report(ImportLog log) {
		String what = this.image.isEmpty() ? "a build context" : "image " + this.image;
		ImageKind kind = this.part.image();
		if (this.labels.containsKey("archsim.node") || kind == null || this.image.startsWith("your-org/")) {
			log.mapped(this.serviceName + ": " + what + " -> " + this.part.displayName()
					+ (this.settings.isEmpty() ? "" : "; " + String.join("; ", this.settings)));
		}
		else {
			guessed(log, kind);
		}
		if (!SERVICE.equals(this.part.type()) && this.deploy.has("replicas")) {
			String type = this.design.registry().find(this.part.type()).orElseThrow().displayName();
			log.ignored(this.serviceName + ".deploy.replicas (a " + type.toLowerCase(Locale.ROOT)
					+ "'s instances are set in the editor)");
		}
		this.service.propertyNames()
			.stream()
			.filter(IGNORED::contains)
			.forEach((key) -> log.ignored(this.serviceName + "." + key));
	}

	private void guessed(ImportLog log, ImageKind kind) {
		if (kind.use() == Use.INFRASTRUCTURE) {
			log.guessed(
					this.serviceName + ": " + this.part.displayName() + ", because '" + this.image + "' " + kind.why());
		}
		else {
			log.guessed(this.serviceName + ": '" + this.image + "' " + kind.why() + ", so it became a service");
		}
		if (!this.settings.isEmpty()) {
			log.mapped(this.serviceName + ": " + String.join("; ", this.settings));
		}
	}

	private void replicas() {
		if (!this.config.has("platform")) {
			this.config.put("platform", "docker");
		}
		if (PythonValues.isInt(this.deploy.path("replicas"))) {
			stated(this.deploy.path("replicas"), "deploy.replicas");
		}
		else if (PythonValues.isInt(this.service.path("scale"))) {
			stated(this.service.path("scale"), "scale");
		}
		else {
			// Compose runs one container unless told otherwise (ArchSim's default is 2)
			this.config.put("replicas", 1);
			this.design.note(this.nodeId, "replicas", "not set: Docker Compose runs one container");
		}
	}

	private void stated(JsonNode replicas, String from) {
		this.config.set("replicas", replicas);
		this.design.note(this.nodeId, "replicas", from);
		this.settings.add("replicas " + PythonValues.str(replicas));
	}

	private void specs() {
		ObjectNode limits = PythonMappings
			.dict(PythonMappings.dict(this.deploy.path("resources"), "get").path("limits"), "get");
		double cpu = Quantities.cpu(limits.path("cpus")).orElse(0);
		double memory = Quantities.memoryGib(limits.path("memory")).orElse(0);
		if (cpu != 0) {
			this.config.put("cpuCores", cpu);
			this.settings.add(PythonFormat.format(cpu, "g") + " CPU");
		}
		if (memory != 0) {
			this.config.put("memoryGib", memory);
			this.settings.add(PythonFormat.format(memory, "g") + " GiB memory");
		}
		for (String key : new String[] { "cpuCores", "memoryGib" }) {
			if (this.config.has(key)) {
				this.design.note(this.nodeId, key, "deploy.resources.limits");
			}
		}
	}

	private void connectionLimit() {
		JsonNode command = this.service.path("command");
		String text = command.isArray()
				? String.join(" ", PythonValues.items(command).stream().map(PythonValues::str).toList())
				: PythonValues.strOrEmpty(command);
		Matcher limit = CONNECTION_LIMIT.matcher(text);
		if (limit.find()) {
			String digits = (limit.group(1) != null) ? limit.group(1) : limit.group(2);
			JsonNode connections = PythonValues.number(PythonValues.toInt(JsonNodeFactory.instance.stringNode(digits)));
			this.config.set("maxConnections", connections);
			this.design.note(this.nodeId, "maxConnections", "from the container command");
			this.settings.add("connection limit " + PythonValues.str(connections));
		}
	}

}
