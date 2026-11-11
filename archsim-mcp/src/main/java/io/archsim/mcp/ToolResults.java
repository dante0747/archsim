package io.archsim.mcp;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.model.text.PythonJson;

/**
 * A tool's answer as the Python SDK made one of a tool function's return: an object as
 * one text, its JSON indented by two with its characters as they are, as
 * {@code pydantic_core.to_json(value, indent=2)} wrote it; an array as one such text for
 * each item; a string as it is. A tool with an output schema gives the value as
 * structured content too, an array or a string under {@code result}. A refusal is one
 * text, {@code Error executing tool NAME: ...}, marked as an error.
 */
final class ToolResults {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final TypeReference<Object> ANY = new TypeReference<>() {
	};

	/** The indent of the text the Python SDK wrote. */
	private static final int INDENT = 2;

	private ToolResults() {
	}

	/**
	 * A tool's value as its answer.
	 * @param value what the tool gave: an object, an array or a string
	 * @param tool the tool
	 * @return the answer
	 */
	static CallToolResult of(JsonNode value, McpSchema.Tool tool) {
		CallToolResult.Builder answer = CallToolResult.builder().isError(false);
		if (value.isArray()) {
			value.forEach((item) -> answer.addTextContent(text(item)));
		}
		else {
			answer.addTextContent(text(value));
		}
		if (tool.outputSchema() != null) {
			JsonNode structured = value.isObject() ? value : JsonNodeFactory.instance.objectNode().set("result", value);
			answer.structuredContent(JSON.convertValue(structured, ANY));
		}
		return answer.build();
	}

	/**
	 * A refusal as its answer.
	 * @param tool the tool's name
	 * @param refusal why the call was refused
	 * @return the answer, marked as an error
	 */
	static CallToolResult refused(String tool, ToolRefusal refusal) {
		return CallToolResult.builder()
			.content(List.of(McpSchema.TextContent.builder("Error executing tool " + tool + ": " + refusal.getMessage())
				.build()))
			.isError(true)
			.build();
	}

	private static String text(JsonNode value) {
		return value.isString() ? value.stringValue() : PythonJson.writePydantic(value, INDENT);
	}

}
