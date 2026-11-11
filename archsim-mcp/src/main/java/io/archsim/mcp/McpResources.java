package io.archsim.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;

import io.archsim.engine.files.PythonFiles;
import io.archsim.model.DesignDocuments;
import io.archsim.model.text.PythonJson;

/**
 * The server's resources, as the Python server gave them: the design document's JSON
 * Schema and each template, as {@code json.dumps(value, indent=1)} wrote them, and the
 * page on what the simulation models. A template that does not exist is the JSON-RPC
 * error the Python SDK answered with.
 */
final class McpResources {

	private static final String SCHEMA = "archsim://schema/architecture";

	private static final String SIMULATION_MODEL = "archsim://docs/simulation-model";

	private static final String TEMPLATES = "archsim://templates/";

	/** The indent of the resources' JSON. */
	private static final int INDENT = 1;

	/** The Python SDK's code for a template it could not make a resource of. */
	private static final int INTERNAL_ERROR = -32603;

	private final ServerDefinitions definitions;

	private final Templates templates;

	private final McpSettings settings;

	/**
	 * The resources.
	 * @param definitions what the server says it offers
	 * @param templates the templates
	 * @param settings where the page on the simulation model is
	 */
	McpResources(ServerDefinitions definitions, Templates templates, McpSettings settings) {
		this.definitions = definitions;
		this.templates = templates;
		this.settings = settings;
	}

	/**
	 * The fixed resources: the schema and the page on the simulation model.
	 * @return their specifications
	 */
	List<SyncResourceSpecification> resources() {
		McpSchema.Resource schema = Objects.requireNonNull(this.definitions.resources().get(SCHEMA), SCHEMA);
		McpSchema.Resource page = Objects.requireNonNull(this.definitions.resources().get(SIMULATION_MODEL),
				SIMULATION_MODEL);
		return List.of(
				new SyncResourceSpecification(schema,
						(exchange, request) -> text(request.uri(), schema.mimeType(),
								PythonJson.writeIndented(DesignDocuments.jsonSchema(), INDENT))),
				new SyncResourceSpecification(page,
						(exchange, request) -> text(request.uri(), page.mimeType(), simulationModel())));
	}

	/**
	 * The templates' resource template.
	 * @return its specification
	 */
	List<SyncResourceTemplateSpecification> templates() {
		return this.definitions.templates()
			.stream()
			.map((template) -> new SyncResourceTemplateSpecification(template,
					(exchange, request) -> template(request.uri(), template.mimeType())))
			.toList();
	}

	private ReadResourceResult template(String uri, String mimeType) {
		try {
			return text(uri, mimeType,
					PythonJson.writeIndented(this.templates.named(uri.substring(TEMPLATES.length())), INDENT));
		}
		catch (ToolRefusal ex) {
			throw McpError.builder(INTERNAL_ERROR)
				.message("Error creating resource from template " + uri)
				.data(Map.of("uri", uri))
				.build();
		}
	}

	private String simulationModel() {
		Path page = this.settings.simulationModel();
		if (page == null || !Files.exists(page)) {
			return "docs/simulation-model.md is not available in this installation.";
		}
		return PythonFiles.readText(page.toString());
	}

	private static ReadResourceResult text(String uri, String mimeType, String text) {
		return ReadResourceResult.builder(List.of(TextResourceContents.builder(uri, text).mimeType(mimeType).build()))
			.build();
	}

}
