package io.archsim.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.files.PythonFileException;
import io.archsim.engine.imports.ImportRefusedException;
import io.archsim.model.codec.BindingException;

/**
 * The server's twelve tools: each definition, as the Python server listed it, with what
 * answers it. A call's refusal, and what the Python server's {@code tool} decorator
 * passed to the agent with its message (a {@code ValueError}, a {@code KeyError}, an
 * {@code OSError}), is a result marked as an error; anything else is the SDK's to answer.
 */
final class McpTools {

	private final Map<String, Function<Arguments, JsonNode>> handlers = new LinkedHashMap<>();

	private final ServerDefinitions definitions;

	/**
	 * The tools.
	 * @param definitions what the server says it offers
	 * @param settings the server's settings
	 * @param templates the templates
	 */
	McpTools(ServerDefinitions definitions, McpSettings settings, Templates templates) {
		this.definitions = definitions;
		CatalogTools catalog = new CatalogTools(templates);
		DesignTools designs = new DesignTools();
		RunTools runs = new RunTools(settings);
		this.handlers.put("list_part_types", catalog::partTypes);
		this.handlers.put("list_templates", catalog::templates);
		this.handlers.put("get_template", catalog::template);
		this.handlers.put("validate_design", designs::validate);
		this.handlers.put("analyze_design", designs::analyze);
		this.handlers.put("simulate", runs::simulate);
		this.handlers.put("get_results", runs::results);
		this.handlers.put("compare", runs::compare);
		this.handlers.put("breaking_point", runs::breakingPoint);
		this.handlers.put("export", designs::export);
		this.handlers.put("calibrate", new CalibrationTool(settings)::calibrate);
		this.handlers.put("import_design", designs::importDesign);
	}

	/**
	 * Each tool's specification, in the order the Python server listed them.
	 * @return the specifications
	 * @throws IllegalStateException when a tool defined has nothing to answer it
	 */
	List<SyncToolSpecification> specifications() {
		return this.definitions.tools().values().stream().map(this::specification).toList();
	}

	private SyncToolSpecification specification(McpSchema.Tool tool) {
		Function<Arguments, JsonNode> handler = this.handlers.get(tool.name());
		if (handler == null) {
			throw new IllegalStateException("no handler for the tool " + tool.name());
		}
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> answer(tool, handler, Arguments.of(request.arguments())))
			.build();
	}

	private McpSchema.CallToolResult answer(McpSchema.Tool tool, Function<Arguments, JsonNode> handler,
			Arguments arguments) {
		try {
			return ToolResults.of(handler.apply(arguments), tool);
		}
		catch (ToolRefusal ex) {
			return ToolResults.refused(tool.name(), ex);
		}
		catch (IllegalArgumentException | BindingException | ImportRefusedException | PythonFileException ex) {
			return ToolResults.refused(tool.name(), new ToolRefusal(String.valueOf(ex.getMessage()), ex));
		}
	}

}
