package io.archsim.mcp;

import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.version.ArchsimVersion;

/**
 * The ArchSim MCP server over stdio: the engine's tools for an AI coding agent, which
 * starts it as {@code archsim-mcp} and talks JSON-RPC on its standard input and output
 * (ADR-0058). It ends when its standard input does, as the Python server did.
 */
public final class ArchsimMcp {

	/** The SDK's JSON: the messages it reads and writes. */
	static final McpJsonMapper JSON = new JacksonMcpJsonMapper(JsonMapper.builder().build());

	/** No limit on a message's length, as in Python: an imported plan can be long. */
	private static final int ANY_LENGTH = Integer.MAX_VALUE;

	private ArchsimMcp() {
	}

	/**
	 * Serves an agent on standard input and output until standard input ends.
	 * @param args none
	 * @throws InterruptedException when the wait for the end of input is interrupted
	 */
	public static void main(String[] args) throws InterruptedException {
		McpSettings settings = McpSettings.of(System.getenv(), installed());
		RequestLines requests = new RequestLines(System.in, JSON);
		McpSyncServer server = server(settings, requests, System.out);
		requests.awaitEnd();
		server.closeGracefully();
		// the transport's threads would keep the JVM running
		System.exit(0);
	}

	/**
	 * The server, on the lines of requests and a stream for its answers.
	 * @param settings what it serves from and how
	 * @param requests the agent's messages
	 * @param answers where the server's messages go
	 * @return the server, serving
	 */
	static McpSyncServer server(McpSettings settings, RequestLines requests, OutputStream answers) {
		ServerDefinitions definitions = ServerDefinitions.read();
		Templates templates = new Templates(settings.examples());
		McpResources resources = new McpResources(definitions, templates, settings);
		return McpServer.sync(new StdioServerTransportProvider(JSON, requests, answers, ANY_LENGTH))
			.serverInfo(definitions.name(), ArchsimVersion.PRODUCT)
			.instructions(definitions.instructions())
			.capabilities(
					McpSchema.ServerCapabilities.builder().prompts(false).resources(false, false).tools(false).build())
			.tools(new McpTools(definitions, settings, templates).specifications())
			.resources(resources.resources())
			.resourceTemplates(resources.templates())
			.build();
	}

	// where the server's own files are, which the templates are looked for above
	private static Path installed() {
		try {
			return Path.of(ArchsimMcp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		}
		catch (URISyntaxException ex) {
			return Path.of("");
		}
	}

}
