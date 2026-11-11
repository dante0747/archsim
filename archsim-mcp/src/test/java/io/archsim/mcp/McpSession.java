package io.archsim.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.modelcontextprotocol.server.McpSyncServer;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A session with the Java server as an agent has one: JSON-RPC messages, one per line,
 * over the SDK's stdio transport, here in-process on two pipes.
 */
final class McpSession implements AutoCloseable {

	/** JSON as the server writes it, NaN included. */
	static final JsonMapper JSON = JsonMapper.builder().enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();

	private final McpSyncServer server;

	private final RequestLines lines;

	private final OutputStream requests;

	private final BufferedReader answers;

	private int next = 1;

	private McpSession(McpSyncServer server, RequestLines lines, OutputStream requests, BufferedReader answers) {
		this.server = server;
		this.lines = lines;
		this.requests = requests;
		this.answers = answers;
	}

	/**
	 * Starts the server.
	 * @param settings what it serves from and how
	 * @return the session
	 * @throws IOException when the pipes cannot be opened
	 */
	static McpSession start(McpSettings settings) throws IOException {
		Pipe toServer = Pipe.open();
		Pipe fromServer = Pipe.open();
		RequestLines lines = new RequestLines(Channels.newInputStream(toServer.source()), ArchsimMcp.JSON);
		McpSyncServer server = ArchsimMcp.server(settings, lines, Channels.newOutputStream(fromServer.sink()));
		return new McpSession(server, lines, Channels.newOutputStream(toServer.sink()), new BufferedReader(
				new InputStreamReader(Channels.newInputStream(fromServer.source()), StandardCharsets.UTF_8)));
	}

	/**
	 * Sends a request and reads its answer.
	 * @param method the method, such as {@code tools/call}
	 * @param params its parameters; {@code null} for none
	 * @return the answer, its {@code id} left out
	 */
	ObjectNode request(String method, @Nullable JsonNode params) {
		int id = this.next++;
		ObjectNode message = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
		if (params != null) {
			message.set("params", params);
		}
		send(message);
		try {
			while (true) {
				String line = this.answers.readLine();
				if (line == null) {
					throw new IllegalStateException("the server closed the session before answering " + method);
				}
				JsonNode answer = JSON.readTree(line);
				if (answer.path("id").asInt(-1) == id && answer instanceof ObjectNode object) {
					object.remove("id");
					return object;
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Sends a notification, which has no answer.
	 * @param method the method, such as {@code notifications/initialized}
	 */
	void notify(String method) {
		send(JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method));
	}

	/**
	 * Sends a line as it is, which need not be a message.
	 * @param line the line, without its line feed
	 */
	void sendLine(String line) {
		try {
			this.requests.write((line + "\n").getBytes(StandardCharsets.UTF_8));
			this.requests.flush();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Ends the server's input, as an agent does when it is done.
	 * @throws IOException when the input cannot be closed
	 */
	void endInput() throws IOException {
		this.requests.close();
	}

	/**
	 * Waits until the server has seen its input end.
	 * @param timeout the longest wait
	 * @return whether it has
	 * @throws InterruptedException when the wait is interrupted
	 */
	boolean ended(Duration timeout) throws InterruptedException {
		return this.lines.awaitEnd(timeout);
	}

	@Override
	public void close() throws IOException {
		this.server.closeGracefully();
		this.requests.close();
	}

	private void send(JsonNode message) {
		sendLine(message.toString());
	}

}
