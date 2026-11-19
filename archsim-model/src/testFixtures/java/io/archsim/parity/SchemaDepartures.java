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
 * alone. Schema 1.9 lets a third-party API's price per million calls be left unset
 * (ADR-0081), where 1.7's defaulted to 0: compared with the frozen engine's output, the
 * Java's reads as it where that output records the price as 0
 * ({@link ApiPriceDepartures}). Schema 1.10 adds {@code objectives.messageAgeP95Ms}
 * (ADR-0084), left out of a document while it is unset. Schema 1.11 counts a vCPU as a
 * hyperthread by default and adds {@code architecture.siblingSpeed} (ADR-0091): a 1.7
 * document is given to the Java pinned to whole cores, as the upgrade pins a saved design
 * ({@link SiblingSpeedDepartures}).
 * <ul>
 * <li>A document the frozen engine was given (a parameter named {@code doc},
 * {@code document} or {@code resolved}, or a report's design) is given to the Java at the
 * current schema as it was written: a 1.7 document is a current one that leaves the hours
 * and the message age out, and its API prices of 0 stay prices of 0, as the frozen engine
 * read them (the upgrade to 1.9 leaves them unset, for a saved design wrote the old
 * default out; the golden files' documents were written as they stand); its vCPUs are
 * pinned to whole cores.</li>
 * <li>A document's {@code schemaVersion} at the current schema reads 1.7, and so does the
 * schema's own version.</li>
 * <li>The upgrades from 1.7 to 1.8, 1.8 to 1.9, 1.9 to 1.10 and 1.10 to 1.11 leave no
 * note.</li>
 * <li>A message that names the supported schema names 1.7.</li>
 * <li>The JSON Schema leaves {@code peakHoursPerMonth} and {@code messageAgeP95Ms} out
 * and names 1.7, and the catalog's list of workload settings leaves the hours out.</li>
 * <li>A document the frozen engine was given at 1.10, its example of a schema newer than
 * its own, is given to the Java at the schema after the current one, and the message that
 * names it names 1.10.</li>
 * </ul>
 */
public final class SchemaDepartures {

	/** The schema the frozen engine wrote. */
	public static final String RECORDED = "1.7";

	private static final String CURRENT = ArchitectureDocument.SCHEMA_VERSION;

	private static final String SCHEMA_VERSION = "schemaVersion";

	private static final String ADDED_WORKLOAD = "peakHoursPerMonth";

	/**
	 * The settings the schema added since 1.7, which the frozen engine's JSON Schema
	 * lacks.
	 */
	private static final Set<String> ADDED = Set.of(ADDED_WORKLOAD, "messageAgeP95Ms");

	/** The notes of the upgrades from the recorded schema to the current one. */
	private static final Set<String> UPGRADE_NOTES = Set.of("migrated schema 1.7 -> 1.8", "migrated schema 1.8 -> 1.9",
			"migrated schema 1.9 -> 1.10", "migrated schema 1.10 -> " + CURRENT);

	/** The frozen engine's example of a schema newer than its own. */
	private static final String NEWER_THAN_RECORDED = "1.10";

	/** A schema newer than the Java's own, given to the Java in its place. */
	private static final String NEWER_THAN_CURRENT = nextMinor(CURRENT);

	/** A message that names that newer schema, up to where it names it. */
	private static final Pattern NAMES_THE_NEWER = Pattern
		.compile("(this document uses schema )" + Pattern.quote(NEWER_THAN_CURRENT) + "\\b");

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
		if (DOCUMENTS.contains(name)) {
			return asCurrent(recorded);
		}
		if (recorded.isObject() && NEWER_THAN_RECORDED.equals(recorded.path(SCHEMA_VERSION).stringValue(""))) {
			ObjectNode newer = (ObjectNode) recorded.deepCopy();
			newer.put(SCHEMA_VERSION, NEWER_THAN_CURRENT);
			return newer;
		}
		return recorded;
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
			SiblingSpeedDepartures.pin(copy);
		}
		if (copy.path("input").isObject()) {
			copy.set("input", asCurrent(copy.path("input")));
		}
		return copy;
	}

	/**
	 * The Java's output as the frozen engine would have written it at schema 1.7, its API
	 * prices read as the frozen engine's output of the same call records them.
	 * @param java the Java's output, which is not changed
	 * @param python the frozen engine's output
	 * @return a copy of it, its schema's departures put back
	 */
	public static JsonNode asRecorded(JsonNode java, JsonNode python) {
		return asRecorded(ApiPriceDepartures.asRecorded(java, python));
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
		String named = NAMES_THE_NEWER.matcher(SiblingSpeedDepartures.asRecordedText(java))
			.replaceAll("$1" + NEWER_THAN_RECORDED);
		return NAMES_THE_SCHEMA.matcher(named).replaceAll("$1" + RECORDED);
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
			boolean addedSetting = "properties".equals(key) && ADDED.contains(member.getKey());
			if (!addedSetting) {
				copy.set(member.getKey(), recorded(member.getValue(), member.getKey()));
			}
		}
		if (CURRENT.equals(copy.path(SCHEMA_VERSION).stringValue(""))) {
			copy.put(SCHEMA_VERSION, RECORDED);
		}
		SiblingSpeedDepartures.leaveOut(copy, key);
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
		return "notes".equals(key) && UPGRADE_NOTES.contains(item.stringValue(""));
	}

	private static boolean isAddedSetting(JsonNode item, @Nullable String key) {
		return "workloadFields".equals(key) && ADDED_WORKLOAD.equals(item.path("key").stringValue(""));
	}

	// the version after another: its last part one more
	private static String nextMinor(String version) {
		int dot = version.lastIndexOf('.');
		return version.substring(0, dot + 1) + (Integer.parseInt(version.substring(dot + 1)) + 1);
	}

}
