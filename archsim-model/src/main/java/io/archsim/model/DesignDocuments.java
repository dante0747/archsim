package io.archsim.model;

import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.issue.SchemaIssues;
import io.archsim.model.upgrade.UpgradeException;
import io.archsim.model.upgrade.Upgrades;

/**
 * Reading and writing design documents: what the editor saves, the API imports and
 * exports, and the engine reads.
 *
 * <pre>{@code
 * ArchitectureDocument design = DesignDocuments.load(json); // any schema from 1.0 on
 * ObjectNode written = DesignDocuments.toJson(design);       // schema 1.9, every default filled in
 * }</pre>
 */
public final class DesignDocuments {

	private DesignDocuments() {
	}

	/**
	 * A document of any supported schema version, upgraded to the current one and read
	 * (the Python engine's {@code load_document}).
	 * @param raw the document's JSON, of schema 1.0 or later
	 * @return the document, in the current schema
	 * @throws DocumentInvalidException with a {@code document.version} issue when it
	 * cannot be upgraded, or a {@code document.schema} issue per thing wrong with it
	 */
	public static ArchitectureDocument load(JsonNode raw) {
		ObjectNode current;
		try {
			current = Upgrades.toCurrent(raw).document();
		}
		catch (UpgradeException ex) {
			throw new DocumentInvalidException(
					List.of(Issue.error("document.version", String.valueOf(ex.getMessage()))));
		}
		return parse(current);
	}

	/**
	 * A document of the current schema, read as it is (pydantic's
	 * {@code model_validate}).
	 * @param json the document's JSON, of the current schema
	 * @return the document
	 * @throws DocumentInvalidException with a {@code document.schema} issue per thing
	 * wrong with it
	 */
	public static ArchitectureDocument parse(JsonNode json) {
		try {
			return RecordCodec.read(json, ArchitectureDocument.class);
		}
		catch (BindingException ex) {
			throw new DocumentInvalidException(SchemaIssues.of(ex.errors(), json, ""));
		}
	}

	/**
	 * The document's JSON, every field written, defaults included (the engine's
	 * {@code to_json_dict}).
	 * @param document the document to write
	 * @return its JSON, of the current schema
	 */
	public static ObjectNode toJson(ArchitectureDocument document) {
		return RecordCodec.write(document);
	}

	/**
	 * The design document's JSON Schema, generated from the records as pydantic writes a
	 * model's (ADR-0025): the API serves it, and AI agents read it. A node's config is
	 * any object here; its component type checks it.
	 * @return the JSON Schema of the current schema version
	 */
	public static ObjectNode jsonSchema() {
		return RecordCodec.jsonSchema(ArchitectureDocument.class);
	}

	/**
	 * The document's JSON with every node's config written in full, its defaults filled
	 * in, so a scenario's override has something to change (the engine's
	 * {@code normalize_document}). A config that is not valid is left as it is;
	 * validation reports it.
	 * @param document the document to write
	 * @param registry the component types that read the nodes' configs
	 * @return its JSON, with every valid node config in full
	 */
	public static ObjectNode normalize(ArchitectureDocument document, ComponentRegistry registry) {
		ObjectNode json = toJson(document);
		for (JsonNode node : json.path("architecture").path("nodes")) {
			Optional<ComponentSpec<?>> spec = registry.find(node.path("type").asString(""));
			if (spec.isEmpty() || !(node instanceof ObjectNode object)) {
				continue;
			}
			try {
				object.set("config", RecordCodec.write(spec.get().parseConfig((ObjectNode) node.get("config"))));
			}
			catch (BindingException ex) {
				// left as it is: validation reports it
			}
		}
		return json;
	}

	/**
	 * The part of a resolved document the simulation reads: its schema version, name,
	 * architecture, workload and pricing. A run's report records it as its input.
	 * @param resolved the document as it runs, with any scenario applied
	 * @return the simulation's input, as JSON
	 */
	public static ObjectNode simulationInput(ArchitectureDocument resolved) {
		ObjectNode json = toJson(resolved);
		ObjectNode input = JsonNodeFactory.instance.objectNode();
		input.set("schemaVersion", json.get("schemaVersion"));
		input.putObject("metadata").set("name", json.get("metadata").get("name"));
		input.set("architecture", json.get("architecture"));
		input.set("workload", json.get("workload"));
		input.set("pricing", json.get("pricing"));
		return input;
	}

}
