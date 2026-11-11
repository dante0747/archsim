package io.archsim.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the server says it offers, as the Python server said it, word for word: its
 * instructions, its tools (names, descriptions, input and output schemas, annotations),
 * its resources and its resource template. They are data, {@code server.json}, taken from
 * the Python server's listing, which {@code McpSessionTests} holds them to.
 *
 * @param name the server's name, as it introduces itself
 * @param instructions what the server tells an agent on connecting
 * @param tools its tools, by name, in the order they are listed
 * @param resources its resources, by URI
 * @param templates its resource templates
 */
record ServerDefinitions(String name, String instructions, Map<String, McpSchema.Tool> tools,
		Map<String, McpSchema.Resource> resources, List<McpSchema.ResourceTemplate> templates) {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
	};

	/**
	 * Copies the definitions.
	 */
	ServerDefinitions {
		tools = Collections.unmodifiableMap(new LinkedHashMap<>(tools));
		resources = Collections.unmodifiableMap(new LinkedHashMap<>(resources));
		templates = List.copyOf(templates);
	}

	/**
	 * The definitions the server is built with.
	 * @return the definitions
	 */
	static ServerDefinitions read() {
		JsonNode server = definitions();
		Map<String, McpSchema.Tool> tools = new LinkedHashMap<>();
		server.get("tools").forEach((tool) -> tools.put(tool.get("name").stringValue(), tool(tool)));
		Map<String, McpSchema.Resource> resources = new LinkedHashMap<>();
		server.get("resources")
			.forEach((resource) -> resources.put(resource.get("uri").stringValue(),
					McpSchema.Resource.builder(resource.get("uri").stringValue(), resource.get("name").stringValue())
						.description(resource.get("description").stringValue())
						.mimeType(resource.get("mimeType").stringValue())
						.build()));
		List<McpSchema.ResourceTemplate> templates = server.get("resourceTemplates")
			.valueStream()
			.map((template) -> McpSchema.ResourceTemplate
				.builder(template.get("uriTemplate").stringValue(), template.get("name").stringValue())
				.description(template.get("description").stringValue())
				.mimeType(template.get("mimeType").stringValue())
				.build())
			.toList();
		return new ServerDefinitions(server.get("name").stringValue(), server.get("instructions").stringValue(), tools,
				resources, templates);
	}

	private static McpSchema.Tool tool(JsonNode tool) {
		JsonNode annotations = tool.get("annotations");
		McpSchema.Tool.Builder builder = McpSchema.Tool
			.builder(tool.get("name").stringValue(), JSON.convertValue(tool.get("inputSchema"), OBJECT))
			.description(tool.get("description").stringValue())
			.annotations(new McpSchema.ToolAnnotations(null, hint(annotations, "readOnlyHint"), null,
					hint(annotations, "idempotentHint"), hint(annotations, "openWorldHint"), null));
		if (tool.has("outputSchema")) {
			builder.outputSchema(JSON.convertValue(tool.get("outputSchema"), OBJECT));
		}
		return builder.build();
	}

	private static @Nullable Boolean hint(JsonNode annotations, String name) {
		JsonNode hint = annotations.get(name);
		return (hint != null) ? hint.asBoolean() : null;
	}

	private static JsonNode definitions() {
		try (InputStream json = Objects.requireNonNull(ServerDefinitions.class.getResourceAsStream("server.json"),
				"server.json")) {
			return JSON.readTree(json);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
