package io.archsim.parity;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.document.ArchitectureDocument;

/**
 * Where the Java's output departs from the frozen engine's because the document schema
 * moved on from 1.7, the one the frozen engine wrote, and how it is compared all the
 * same. Schema 1.8 adds {@code workload.peakHoursPerMonth} (ADR-0074), left out of a
 * document while it is 0, so a document differs from the frozen engine's by its version
 * alone.
 * <ul>
 * <li>A document the frozen engine was given (a parameter named {@code doc},
 * {@code document} or {@code resolved}, or a report's design) is given to the Java at
 * 1.8: a 1.7 document is a 1.8 one that leaves the hours out.</li>
 * <li>A document's {@code schemaVersion} of 1.8 reads 1.7, and so does the schema's own
 * version.</li>
 * <li>The upgrade from 1.7 to 1.8 leaves no note.</li>
 * <li>A message that names the supported schema names 1.7.</li>
 * <li>The JSON Schema leaves {@code peakHoursPerMonth} out and names 1.7, and so does the
 * catalog's list of workload settings.</li>
 * </ul>
 */
public final class SchemaDepartures {

	/** The schema the frozen engine wrote. */
	public static final String RECORDED = "1.7";

	private static final String CURRENT = ArchitectureDocument.SCHEMA_VERSION;

	private static final String SCHEMA_VERSION = "schemaVersion";

	private static final String ADDED = "peakHoursPerMonth";

	private static final String UPGRADE_NOTE = "migrated schema " + RECORDED + " -> " + CURRENT;

	/** The messages that name the supported schema, up to where they name it. */
	private static final Pattern NAMES_THE_SCHEMA = Pattern
		.compile("(no migration path from schema .* to |newer than the supported )" + Pattern.quote(CURRENT) + "\\b");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The parameters that are documents the Java reads at the current schema. */
	private static final Set<String> DOCUMENTS = Set.of("doc", "document", "resolved", "report");

	private SchemaDepartures() {
	}

	/**
	 * A parameter of a recorded call as the Java is given it: a document at the current
	 * schema.
	 * @param name the parameter's name
	 * @param recorded what the frozen engine was given, which is not changed
	 * @return a copy of a document, or a report's, at the current schema; any other
	 * parameter as recorded
	 */
	public static JsonNode asGiven(String name, JsonNode recorded) {
		return DOCUMENTS.contains(name) ? asCurrent(recorded) : recorded;
	}

	/**
	 * A document the frozen engine wrote, or a report of one, at the current schema.
	 * @param node the document or report, which is not changed
	 * @return a copy of it at the current schema; the node itself when it is not an
	 * object
	 */
	public static JsonNode asCurrent(JsonNode node) {
		if (!node.isObject()) {
			return node;
		}
		ObjectNode copy = (ObjectNode) node.deepCopy();
		if (RECORDED.equals(copy.path(SCHEMA_VERSION).stringValue(""))) {
			copy.put(SCHEMA_VERSION, CURRENT);
		}
		if (copy.path("input").isObject()) {
			copy.set("input", asCurrent(copy.path("input")));
		}
		return copy;
	}

	/**
	 * The Java's output as the frozen engine would have written it at schema 1.7.
	 * @param java the Java's output, which is not changed
	 * @return a copy of it, its schema's departures put back
	 */
	public static JsonNode asRecorded(JsonNode java) {
		if (CURRENT.equals(java.stringValue(""))) {
			// the schema version itself
			return NODES.stringNode(RECORDED);
		}
		return recorded(java, null);
	}

	/**
	 * Text the Java printed as the frozen engine would have printed it at schema 1.7: a
	 * message that names the supported schema names 1.7.
	 * @param java the Java's text
	 * @return the text, its schema's departures put back
	 */
	public static String asRecordedText(String java) {
		return NAMES_THE_SCHEMA.matcher(java).replaceAll("$1" + RECORDED);
	}

	private static JsonNode recorded(JsonNode node, @Nullable String key) {
		if (node.isObject()) {
			return recordedObject((ObjectNode) node, key);
		}
		if (node.isArray()) {
			ArrayNode copy = NODES.arrayNode();
			node.forEach((item) -> {
				if (!isAdded(item, key)) {
					copy.add(recorded(item, null));
				}
			});
			return copy;
		}
		if (node.isString()) {
			return NODES.stringNode(asRecordedText(node.stringValue()));
		}
		return node;
	}

	private static ObjectNode recordedObject(ObjectNode node, @Nullable String key) {
		ObjectNode copy = NODES.objectNode();
		for (Map.Entry<String, JsonNode> member : node.properties()) {
			boolean addedSetting = "properties".equals(key) && ADDED.equals(member.getKey());
			if (!addedSetting) {
				copy.set(member.getKey(), recorded(member.getValue(), member.getKey()));
			}
		}
		if (CURRENT.equals(copy.path(SCHEMA_VERSION).stringValue(""))) {
			copy.put(SCHEMA_VERSION, RECORDED);
		}
		if (SCHEMA_VERSION.equals(key)) {
			// the JSON Schema's property: its fixed and default version
			putRecorded(copy, "const");
			putRecorded(copy, "default");
		}
		return copy;
	}

	private static void putRecorded(ObjectNode property, String key) {
		if (CURRENT.equals(property.path(key).stringValue(""))) {
			property.put(key, RECORDED);
		}
	}

	// the upgrade step's note among an upgrade's notes, and the added setting among the
	// catalog's workload settings
	private static boolean isAdded(JsonNode item, @Nullable String key) {
		return isUpgradeNote(item, key) || isAddedSetting(item, key);
	}

	private static boolean isUpgradeNote(JsonNode item, @Nullable String key) {
		return "notes".equals(key) && UPGRADE_NOTE.equals(item.stringValue(""));
	}

	private static boolean isAddedSetting(JsonNode item, @Nullable String key) {
		return "workloadFields".equals(key) && ADDED.equals(item.path("key").stringValue(""));
	}

}
