package io.archsim.mcp;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool's value as the Python SDK made a result of it, for the lists the recorded
 * session cannot show: its one tool returning a list, {@code list_templates}, lists every
 * template.
 */
class ToolResultsTests {

	private static final McpSchema.Tool LISTING = McpSchema.Tool.builder("list_templates", Map.of("type", "object"))
		.outputSchema(Map.of("type", "object"))
		.build();

	@Test
	void of_aShortList_isOneTextPerItemAndTheListAsResult() {
		JsonNode value = McpSession.JSON.readTree("[{\"id\": \"a\"}, \"b\"]");

		CallToolResult result = ToolResults.of(value, LISTING);

		assertThat(result.content()).extracting((content) -> ((McpSchema.TextContent) content).text())
			.containsExactly("{\n  \"id\": \"a\"\n}", "b");
		assertThat(result.structuredContent()).isEqualTo(Map.of("result", List.of(Map.of("id", "a"), "b")));
		assertThat(result.isError()).isFalse();
	}

	@Test
	void of_anEmptyList_isNoTextAndTheEmptyListAsResult() {
		CallToolResult result = ToolResults.of(McpSession.JSON.readTree("[]"), LISTING);

		assertThat(result.content()).isEmpty();
		assertThat(result.structuredContent()).isEqualTo(Map.of("result", List.of()));
	}

}
