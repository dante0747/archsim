package io.archsim.engine.imports;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.load.ComponentFamily;
import io.archsim.engine.roundtrip.ExportedLink;
import io.archsim.engine.roundtrip.RoundTrip;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.Port;
import io.archsim.model.document.ArchitectureDocument;

/**
 * The design an importer builds, part by part and link by link, with how each imported
 * number was derived and which parts came back from ArchSim's own export.
 */
final class DesignBuilder {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The longest label or name a design takes, in characters (or a list's items). */
	private static final int LABEL_LENGTH = 120;

	/** The longest base of a part id an import makes. */
	private static final int ID_LENGTH = 60;

	/** How far apart the layout's columns and rows are. */
	private static final int COLUMN_WIDTH = 300;

	private static final int ROW_HEIGHT = 170;

	/** The layout's column of each kind of part: clients left, third parties right. */
	private static final Map<ComponentFamily, Integer> COLUMNS = Map.of(ComponentFamily.TRAFFIC, 0,
			ComponentFamily.NETWORK, 1, ComponentFamily.COMPUTE, 2, ComponentFamily.CACHE, 3, ComponentFamily.MESSAGING,
			3, ComponentFamily.DATABASE, 4, ComponentFamily.EXTERNAL, 5, ComponentFamily.OTHER, 2);

	private static final Pattern NOT_ID = Pattern.compile("[^A-Za-z0-9_.-]+");

	private static final Pattern EDGE_DOTS_AND_DASHES = Pattern.compile("^[-.]+|[-.]+$");

	private static final Pattern STARTS_AN_ID = Pattern.compile("^[A-Za-z0-9]");

	private final ComponentRegistry registry;

	private final JsonNode name;

	private final String source;

	private final List<ObjectNode> nodes = new ArrayList<>();

	private final List<ObjectNode> edges = new ArrayList<>();

	private final Map<String, String> notes = new LinkedHashMap<>();

	private final Set<String> fromArchsim = new HashSet<>();

	/**
	 * A design to build.
	 * @param registry the component types
	 * @param name the design's name, as written: cut to 120 characters (or items) when
	 * the document is written
	 * @param source what is imported, for the provenance of its numbers
	 */
	DesignBuilder(ComponentRegistry registry, JsonNode name, String source) {
		this.registry = registry;
		this.name = name;
		this.source = source;
	}

	ComponentRegistry registry() {
		return this.registry;
	}

	/**
	 * The parts so far, whose settings the import's last steps still change.
	 * @return the parts, in the order added
	 */
	List<ObjectNode> nodes() {
		return Collections.unmodifiableList(this.nodes);
	}

	/**
	 * The links so far, whose settings the import's last steps still change.
	 * @return the links, in the order added
	 */
	List<ObjectNode> edges() {
		return Collections.unmodifiableList(this.edges);
	}

	/**
	 * Notes how an imported number was derived, shown with its provenance.
	 * @param nodeId the part
	 * @param key the setting
	 * @param text how it was derived
	 */
	void note(String nodeId, String key, String text) {
		this.notes.put("nodes/" + nodeId + "/" + key, text);
	}

	boolean has(String nodeId) {
		return this.nodes.stream().anyMatch((node) -> node.path("id").stringValue().equals(nodeId));
	}

	/**
	 * A part id from a name, unlike any part's so far.
	 * @param base the name
	 * @return the name with what an id cannot hold replaced by dashes, at most 60
	 * characters, starting with a letter or digit, and numbered when it is taken
	 */
	String uniqueId(String base) {
		String id = EDGE_DOTS_AND_DASHES.matcher(NOT_ID.matcher(base).replaceAll("-")).replaceAll("");
		id = id.substring(0, Math.min(id.length(), ID_LENGTH));
		if (id.isEmpty()) {
			id = "part";
		}
		if (!STARTS_AN_ID.matcher(id).find()) {
			id = "p" + id;
		}
		String unique = id;
		for (int next = 2; has(unique); next++) {
			unique = id + "-" + next;
		}
		return unique;
	}

	/**
	 * Adds a part.
	 * @param nodeId its id
	 * @param type its type
	 * @param label its label, cut to 120 characters
	 * @param config its settings
	 * @return the part
	 */
	ObjectNode add(String nodeId, String type, String label, ObjectNode config) {
		return add(nodeId, type, NODES.stringNode(label), config);
	}

	/**
	 * Adds a part labelled by a name as the file wrote it.
	 * @param nodeId its id
	 * @param type its type
	 * @param label its label, as written: cut to 120 characters (or a list's items), as
	 * Python cuts it
	 * @param config its settings
	 * @return the part
	 * @throws ImportShapeException for a label that cannot be cut: a mapping, a number
	 */
	ObjectNode add(String nodeId, String type, JsonNode label, ObjectNode config) {
		ObjectNode node = NODES.objectNode().put("id", nodeId).put("type", type);
		node.set("label", PythonValues.sliced(label, LABEL_LENGTH));
		node.set("config", config);
		this.nodes.add(node);
		return node;
	}

	Optional<ObjectNode> node(String nodeId) {
		return this.nodes.stream().filter((node) -> node.path("id").stringValue().equals(nodeId)).findFirst();
	}

	/**
	 * Adds a link, once.
	 * @param source the calling part
	 * @param link the part it calls, its port, request types and settings, as an export
	 * wrote them or as an importer infers them
	 * @return whether it was added: not when either part is missing, the parts are one,
	 * the link is there already, or the caller has no output port
	 */
	boolean link(String source, ExportedLink link) {
		String target = link.target();
		Optional<ObjectNode> caller = node(source);
		if (caller.isEmpty() || node(target).isEmpty() || source.equals(target)) {
			return false;
		}
		if (this.edges.stream().anyMatch((edge) -> isBetween(edge, source, target))) {
			return false;
		}
		List<String> outs = this.registry.find(caller.get().path("type").stringValue())
			.map((spec) -> spec.outPorts().stream().map(Port::name).toList())
			.orElse(List.of("out"));
		if (outs.isEmpty()) {
			return false;
		}
		String port = (link.port() != null && outs.contains(link.port())) ? link.port() : outs.getFirst();
		ObjectNode edge = NODES.objectNode()
			.put("id", edgeId(source, target))
			.put("source", source)
			.put("sourcePort", port)
			.put("target", target)
			.put("targetPort", "in");
		ObjectNode config = edge.putObject("config");
		ArrayNode types = config.putArray("requestTypes");
		List<String> carried = link.requestTypes();
		((carried == null || carried.isEmpty()) ? List.of("read", "write") : carried).forEach(types::add);
		link.settings().forEach(config::put);
		this.edges.add(edge);
		return true;
	}

	/**
	 * Adds a link that carries every request type, once.
	 * @param source the calling part
	 * @param target the called part
	 * @return whether it was added
	 */
	boolean link(String source, String target) {
		return link(source, new ExportedLink(target, null, null, Map.of()));
	}

	/**
	 * Adds ArchSim's own parts (clients, clusters) from an export; their links come with
	 * {@link #linkParts(List)}.
	 * @param parts the parts the export carries
	 */
	void addParts(List<ObjectNode> parts) {
		for (ObjectNode part : parts) {
			String partId = part.path("id").stringValue();
			if (has(partId) || this.registry.find(part.path("type").stringValue()).isEmpty()) {
				continue;
			}
			JsonNode label = part.path("label");
			add(partId, part.path("type").stringValue(), PythonValues.truthy(label) ? PythonValues.str(label) : partId,
					PythonMappings.dictOf(part.path("config")));
			this.fromArchsim.add(partId);
		}
	}

	/**
	 * Adds the links of ArchSim's own parts.
	 * @param parts the parts the export carries
	 */
	void linkParts(List<ObjectNode> parts) {
		for (ObjectNode part : parts) {
			for (ExportedLink link : RoundTrip.decodeCalls(PythonValues.strOrEmpty(part.path("calls")))) {
				link(part.path("id").stringValue(), link);
			}
		}
	}

	ComponentFamily kind(String nodeId) {
		return node(nodeId).map((node) -> ComponentFamily.of(node.path("type").stringValue(), this.registry))
			.orElse(ComponentFamily.OTHER);
	}

	List<String> callers(String nodeId) {
		return this.edges.stream()
			.filter((edge) -> edge.path("target").stringValue().equals(nodeId))
			.map((edge) -> edge.path("source").stringValue())
			.toList();
	}

	/**
	 * The parts a part calls.
	 * @param nodeId the part
	 * @param port only its links from this port; {@code null} for all of them
	 * @return the parts, in the links' order
	 */
	List<String> callees(String nodeId, @Nullable String port) {
		return this.edges.stream()
			.filter((edge) -> edge.path("source").stringValue().equals(nodeId))
			.filter((edge) -> port == null || edge.path("sourcePort").stringValue().equals(port))
			.map((edge) -> edge.path("target").stringValue())
			.toList();
	}

	/**
	 * The design: its parts laid out in columns by kind, its links, and where each number
	 * came from.
	 * @return the design document, not yet validated
	 */
	ObjectNode document() {
		Map<Integer, Integer> rows = new LinkedHashMap<>();
		ObjectNode layout = NODES.objectNode();
		for (ObjectNode node : this.nodes) {
			int column = COLUMNS.getOrDefault(ComponentFamily.of(node.path("type").stringValue(), this.registry), 2);
			int row = rows.merge(column, 1, Integer::sum) - 1;
			layout.putObject(node.path("id").stringValue()).put("x", column * COLUMN_WIDTH).put("y", row * ROW_HEIGHT);
		}
		ObjectNode document = NODES.objectNode()
			.put("schemaVersion", ArchitectureDocument.SCHEMA_VERSION)
			.put("kind", "archsim.architecture");
		JsonNode designName = PythonValues.sliced(this.name, LABEL_LENGTH);
		document.putObject("metadata")
			.set("name", PythonValues.truthy(designName) ? designName : NODES.stringNode("Imported design"));
		ObjectNode architecture = document.putObject("architecture");
		this.nodes.forEach(architecture.putArray("nodes")::add);
		this.edges.forEach(architecture.putArray("edges")::add);
		document.putObject("workload");
		document.putArray("scenarios");
		document.putObject("layout").set("nodes", layout);
		ObjectNode provenance = provenance();
		if (!provenance.isEmpty()) {
			document.set("provenance", provenance);
		}
		return document;
	}

	/**
	 * Every number the import set, assumed (someone wrote it in the file), with its
	 * source and, when it was derived or guessed, how.
	 * @return {@code {nodes/<id>/<key>: {kind, source, note}}}
	 */
	private ObjectNode provenance() {
		ObjectNode provenance = NODES.objectNode();
		for (ObjectNode node : this.nodes) {
			String nodeId = node.path("id").stringValue();
			String origin = this.fromArchsim.contains(nodeId) ? "ArchSim's export" : this.source;
			for (Map.Entry<String, JsonNode> setting : node.path("config").properties()) {
				if (setting.getValue().isNumber()) {
					String path = "nodes/" + nodeId + "/" + setting.getKey();
					ObjectNode entry = provenance.putObject(path)
						.put("kind", "assumed")
						.put("source", "imported from " + origin);
					String note = this.notes.get(path);
					if (note != null) {
						entry.put("note", note);
					}
				}
			}
		}
		return provenance;
	}

	private String edgeId(String source, String target) {
		String edgeId = source + "-" + target;
		for (int next = 2; isTaken(edgeId); next++) {
			edgeId = source + "-" + target + "-" + next;
		}
		return edgeId;
	}

	private boolean isTaken(String edgeId) {
		return this.edges.stream().anyMatch((edge) -> edge.path("id").stringValue().equals(edgeId));
	}

	private static boolean isBetween(ObjectNode edge, String source, String target) {
		return edge.path("source").stringValue().equals(source) && edge.path("target").stringValue().equals(target);
	}

}
