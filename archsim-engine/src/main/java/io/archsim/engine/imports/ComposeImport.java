package io.archsim.engine.imports;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.roundtrip.RoundTrip;
import io.archsim.model.component.ComponentRegistry;

/**
 * A design from a docker-compose file. Services become parts by their image's full name
 * (listed as guesses); {@code deploy.replicas} (one container when unset) and resource
 * limits become replicas and specs; links come from {@code depends_on} and from
 * environment values that are URLs naming another service (guesses). ArchSim's own
 * exports carry {@code archsim.*} labels (part ids, links, some settings) and an
 * {@code x-archsim} block (clients, clusters), read back as they were written.
 */
public final class ComposeImport {

	/** What the text must be, in the words of a refusal. */
	private static final String SHAPE = "a docker-compose file";

	/** The top-level keys the import reads; any other is listed as ignored. */
	private static final Set<String> TOP_LEVEL = Set.of("services", "name", "volumes", "x-external-dependencies",
			"x-archsim", "version");

	private final ComponentRegistry registry;

	/**
	 * Imports designs made of a registry's types.
	 * @param registry the component types
	 */
	public ComposeImport(ComponentRegistry registry) {
		this.registry = registry;
	}

	/**
	 * A design from a docker-compose file, before validation, with the import report.
	 * @param text the file's content
	 * @param name the design's name; {@code null} or empty for the file's
	 * @return the design and the report
	 * @throws ImportRefusedException when the text is not YAML, or not a docker-compose
	 * file the import can read
	 */
	public ImportedDesign read(String text, @Nullable String name) {
		try {
			return compose(text, name);
		}
		catch (ImportShapeException ex) {
			throw new ImportRefusedException("this is not " + SHAPE + " the import can read (" + ex.getMessage() + ")",
					ex);
		}
	}

	private ImportedDesign compose(String text, @Nullable String name) {
		JsonNode data = composeFile(text);
		ImportLog log = new ImportLog("docker-compose");
		DesignBuilder design = new DesignBuilder(this.registry, JsonNodeFactory.instance.stringNode(title(data, name)),
				"docker-compose");
		data.propertyNames()
			.stream()
			.filter((key) -> !TOP_LEVEL.contains(key))
			.forEach((key) -> log.ignored("top-level '" + key + "'"));
		PartNames names = new PartNames();
		ComposeServices services = new ComposeServices(design, log, names);
		data.path("services").properties().forEach((service) -> services.read(service.getKey(), service.getValue()));
		for (JsonNode dependency : PythonValues.items(data.path("x-external-dependencies"))) {
			external(design, log, names, PythonMappings.mapping(dependency));
		}
		List<ObjectNode> parts = RoundTrip.parseParts(PythonMappings.dict(data.path("x-archsim"), "get").path("parts"));
		design.addParts(parts);
		services.link(data.path("services"));
		linkExternals(design, names);
		design.linkParts(parts);
		if (!parts.isEmpty()) {
			log.mapped("x-archsim: " + ExportedParts.ids(parts) + " (parts ArchSim exported but nothing deploys)");
		}
		new ImportFinish(design, log).finish();
		if (!ExportedParts.has(parts, "traffic_source")) {
			new EntryPoint(design, log, services.tools()).addClients(services.published());
		}
		return new ImportedDesign(design.document(), log.report());
	}

	/**
	 * A docker-compose file's content.
	 * @param text the file
	 * @return its content: a mapping with a {@code services} mapping
	 * @throws ImportRefusedException when it is not YAML, or has no {@code services}
	 * mapping
	 */
	private static JsonNode composeFile(String text) {
		JsonNode loaded = YamlDocuments.one(text);
		JsonNode data = PythonValues.truthy(loaded) ? loaded : JsonNodeFactory.instance.objectNode();
		if (!data.isObject() || !data.path("services").isObject()) {
			throw new ImportRefusedException("not a docker-compose file: no 'services' mapping");
		}
		return data;
	}

	private static String title(JsonNode data, @Nullable String name) {
		if (name != null && !name.isEmpty()) {
			return name;
		}
		String title = PythonValues.strOrEmpty(data.path("name"));
		return title.isEmpty() ? "Imported from docker-compose" : title;
	}

	/**
	 * A third party or managed service an export listed apart
	 * ({@code x-external-dependencies}).
	 * @param design the design
	 * @param log the report
	 * @param names the links parts state, which the entry's join
	 * @param dependency the entry
	 */
	private static void external(DesignBuilder design, ImportLog log, PartNames names, ObjectNode dependency) {
		String technology = PythonValues.strOrEmpty(dependency.path("technology"));
		String nodeId = design
			.uniqueId(firstTruthy(dependency, "id", "name").orElse(technology.isEmpty() ? "external" : technology));
		String label = firstTruthy(dependency, "label", "name").orElse(nodeId);
		if (PythonValues.truthy(dependency.path("calls"))) {
			names.stateCalls(nodeId, PythonValues.str(dependency.path("calls")));
		}
		ObjectNode config = JsonNodeFactory.instance.objectNode();
		Optional<String> managed = ManagedTechnologies.typeOf(technology);
		if (managed.isPresent()) {
			design.add(nodeId, managed.get(), label, config.put("technology", technology));
			log.mapped(label + ": managed " + technology + " (x-external-dependencies)");
			return;
		}
		if (ManagedTechnologies.isThirdParty(technology)) {
			config.put("technology", technology);
		}
		design.add(nodeId, "external_api", label, config);
		log.mapped(label + ": third-party API (x-external-dependencies)");
	}

	/**
	 * Adds the stated links of the parts no service deploys: managed services, third
	 * parties.
	 * @param design the design
	 * @param names the services' parts, and the links parts state
	 */
	private static void linkExternals(DesignBuilder design, PartNames names) {
		Set<String> deployed = new HashSet<>(names.partIds());
		names.statedCalls().forEach((source, text) -> {
			if (!deployed.contains(source)) {
				RoundTrip.decodeCalls(text).forEach((call) -> design.link(source, call));
			}
		});
	}

	/**
	 * The first of a mapping's fields that counts as true, as text.
	 * @param mapping the mapping
	 * @param fields the fields, in order
	 * @return the field's text; empty when none counts as true
	 */
	private static Optional<String> firstTruthy(ObjectNode mapping, String... fields) {
		for (String field : fields) {
			if (PythonValues.truthy(mapping.path(field))) {
				return Optional.of(PythonValues.str(mapping.path(field)));
			}
		}
		return Optional.empty();
	}

}
