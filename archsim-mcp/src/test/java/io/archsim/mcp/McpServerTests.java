package io.archsim.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.simulation.SimulationLimits;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the recorded session cannot show: a server without its templates' folder, a page
 * saved on Windows, a recording that names no source, input that is not a message or is
 * longer than the SDK reads by default, and what the server logs, which must never reach
 * standard output, the protocol's.
 */
@Timeout(60)
class McpServerTests {

	private static final String SLF4J_PROVIDERS = "META-INF/services/org.slf4j.spi.SLF4JServiceProvider";

	private static final SimulationLimits LIMITS = new SimulationLimits(2_000_000, 120);

	/** A message longer than the 16 MiB the SDK's transport reads by default. */
	private static final int LONG_MESSAGE = 17 * 1024 * 1024;

	@Test
	void server_noTemplatesFolder_saysThePageIsNotThere() throws IOException {
		try (McpSession session = started(null)) {
			JsonNode page = session.request("resources/read",
					McpSession.JSON.readTree("{\"uri\": \"archsim://docs/simulation-model\"}"));

			assertThat(page.at("/result/contents/0/text").stringValue())
				.isEqualTo("docs/simulation-model.md is not available in this installation.");
		}
	}

	@Test
	void server_noTemplatesFolder_listsNoTemplates() throws IOException {
		try (McpSession session = started(null)) {
			JsonNode templates = session.request("tools/call",
					McpSession.JSON.readTree("{\"name\": \"list_templates\", \"arguments\": {}}"));

			assertThat(templates.at("/result/structuredContent/result").isEmpty()).isTrue();
		}
	}

	@Test
	void server_aTemplatesFolderThatIsNotThere_hasNoTemplates(@TempDir Path folder) throws IOException {
		// as Python's glob found none in it
		try (McpSession session = started(folder.resolve("nowhere"))) {
			JsonNode template = session.request("tools/call",
					McpSession.JSON.readTree("{\"name\": \"get_template\", \"arguments\": {\"id\": \"x\"}}"));

			assertThat(template.at("/result/content/0/text").stringValue())
				.isEqualTo("Error executing tool get_template: unknown template 'x'; templates: ");
		}
	}

	@Test
	void server_pageSavedWithOtherLineEnds_givesItWithLineFeeds(@TempDir Path installation) throws IOException {
		// as Python's read_text read it: \r\n and \r each a line feed
		Path examples = Files.createDirectories(installation.resolve("examples"));
		Files.writeString(examples.resolve("queueing-lab.json"), "{}");
		Files.createDirectories(installation.resolve("docs"));
		Files.writeString(installation.resolve("docs/simulation-model.md"), "# Model\r\nqueues\rand links\n");
		try (McpSession session = started(examples)) {
			JsonNode page = session.request("resources/read",
					McpSession.JSON.readTree("{\"uri\": \"archsim://docs/simulation-model\"}"));

			assertThat(page.at("/result/contents/0/text").stringValue()).isEqualTo("# Model\nqueues\nand links\n");
		}
	}

	@Test
	void calibrate_aRecordingNamingNoSource_namesItByItsFile(@TempDir Path folder) throws IOException {
		// as RecordedPrometheus.load named it: "recorded responses " + Path(path).name
		JsonNode files = McpSessionTests.RECORDED.get("files");
		ObjectNode recording = (ObjectNode) McpSession.JSON
			.readTree(files.get("{fixtures}/calibration/starter-prometheus.json").stringValue());
		recording.remove("source");
		Path mapping = folder.resolve("mapping.yaml");
		Path unnamed = Files.createDirectories(folder.resolve("recordings")).resolve("unnamed.json");
		Files.writeString(mapping, files.get("{fixtures}/calibration/starter-mapping.yaml").stringValue());
		Files.writeString(unnamed, recording.toString());
		ObjectNode arguments = McpSession.JSON.createObjectNode()
			.put("source", "prometheus")
			.put("mapping_path", mapping.toString())
			.put("recorded_path", unnamed.toString());
		arguments.set("document",
				McpSession.JSON.readTree(files.get("examples/cache-aside-starter.json").stringValue()));
		try (McpSession session = started(null)) {
			JsonNode answer = session.request("tools/call",
					McpSession.JSON.createObjectNode().put("name", "calibrate").set("arguments", arguments));

			assertThat(answer.at("/result/structuredContent/calibration/source").stringValue())
				.isEqualTo("recorded responses unnamed.json");
		}
	}

	@Test
	void session_linesThatAreNotMessages_areSkippedAndTheNextRequestAnswered() throws IOException {
		// as the Python SDK skipped them, where the Java SDK's transport stops reading
		try (McpSession session = started(null)) {
			List.of("{not json", "{}", "", "null").forEach(session::sendLine);

			JsonNode pong = session.request("ping", null);

			assertThat(pong.get("result")).isEqualTo(McpSession.JSON.createObjectNode());
		}
	}

	@Test
	void session_inputEndingAfterALineThatIsNotAMessage_ends() throws IOException, InterruptedException {
		try (McpSession session = started(null)) {
			session.sendLine("{not json");

			session.endInput();

			assertThat(session.ended(Duration.ofSeconds(10))).isTrue();
		}
	}

	@Test
	void session_aMessageLongerThanTheSdksDefault_isAnswered() throws IOException {
		// an imported plan can be that long; Python read a line of any length
		try (McpSession session = started(null)) {
			JsonNode pong = session.request("ping",
					McpSession.JSON.createObjectNode().put("padding", "x".repeat(LONG_MESSAGE)));

			assertThat(pong.get("result")).isEqualTo(McpSession.JSON.createObjectNode());
		}
	}

	@Test
	void slf4jProviders_onTheServersClassPath_areTheNoOperationOneAlone() throws IOException {
		// a provider that wrote to standard output would break the protocol
		List<String> providers = new ArrayList<>();
		for (URL found : Collections.list(getClass().getClassLoader().getResources(SLF4J_PROVIDERS))) {
			try (InputStream in = found.openStream()) {
				providers.add(new String(in.readAllBytes(), StandardCharsets.UTF_8).strip());
			}
		}

		assertThat(providers).containsExactly("org.slf4j.nop.NOPServiceProvider");
	}

	// a server serving the templates of a folder, initialized
	private static McpSession started(@Nullable Path examples) throws IOException {
		McpSession session = McpSession.start(new McpSettings(examples, LIMITS, Clock.systemUTC()));
		session.request("initialize", McpSession.JSON.readTree("""
				{"protocolVersion": "2025-06-18", "capabilities": {},
				 "clientInfo": {"name": "test", "version": "1"}}"""));
		session.notify("notifications/initialized");
		return session;
	}

}
