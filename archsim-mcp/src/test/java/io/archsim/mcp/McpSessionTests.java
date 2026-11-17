package io.archsim.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.version.EngineVersions;
import io.archsim.model.text.PythonText;

/**
 * The Java server against the frozen Python server: each request of
 * {@code session.json.gz} ({@code scripts/export_mcp_cases.py}, since deleted) sent, in
 * order, to one Java server, serving the files the Python server served, and its answer
 * compared with Python's ({@link Answers}): the same tools, resources and instructions,
 * the same results, the same refusals, but for the engine a result names, its own, and
 * the departures ADR-0058 lists, checked here as they are.
 */
class McpSessionTests {

	/** The recorded session: its requests and answers, and the files it read. */
	static final JsonNode RECORDED = recorded();

	/** The limits the Python server ran with, its defaults. */
	private static final SimulationLimits LIMITS = new SimulationLimits(2_000_000, 120);

	/** The time the recording's calibrations stamped. */
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);

	@TestFactory
	Stream<DynamicTest> session_eachRequestRecorded_isAnsweredAsThePythonServerAnsweredIt(@TempDir Path folder)
			throws IOException {
		for (Map.Entry<String, JsonNode> file : RECORDED.get("files").properties()) {
			Path written = folder.resolve(file.getKey().replace(Answers.FIXTURES + "/", ""));
			Files.createDirectories(Objects.requireNonNull(written.getParent()));
			Files.writeString(written, file.getValue().stringValue());
		}
		// the templates and page the Python server served, recorded with the session
		McpSession session = McpSession.start(new McpSettings(folder.resolve("examples"), LIMITS, CLOCK));
		Answers answers = new Answers(session, folder.toString().replace('\\', '/'));
		return RECORDED.get("exchanges")
			.valueStream()
			.map((exchange) -> DynamicTest.dynamicTest(name(exchange), () -> answers.check(exchange)))
			.onClose(() -> close(session));
	}

	private static String name(JsonNode exchange) {
		JsonNode request = exchange.get("request");
		String tool = request.at("/params/name").stringValue("");
		return request.get("method").stringValue() + (tool.isEmpty() ? "" : " " + tool) + " "
				+ request.at("/params/arguments").toString() + request.at("/params/uri").stringValue("");
	}

	private static void close(McpSession session) {
		try {
			session.close();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static JsonNode recorded() {
		try (InputStream compressed = Objects
			.requireNonNull(McpSessionTests.class.getResourceAsStream("session.json.gz"), "session.json.gz");
				InputStream json = new GZIPInputStream(compressed)) {
			return McpSession.JSON.readTree(json);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * What the server answers, against what Python's answered.
	 */
	static final class Answers {

		/** Stands for the folder of the files the calibrations read. */
		static final String FIXTURES = "{fixtures}";

		/** Stands for the first run's id in the requests that name it. */
		private static final String RUN = "{run}";

		/** A file a request names, by its placeholder. */
		private static final Pattern NAMED_FILE = Pattern.compile(Pattern.quote(FIXTURES) + "/[\\w./-]+");

		private final McpSession session;

		private final String fixtures;

		private String run = "";

		private final RunsPricedAtZero pricedAtZero = new RunsPricedAtZero();

		Answers(McpSession session, String fixtures) {
			this.session = session;
			this.fixtures = fixtures;
		}

		void check(JsonNode exchange) {
			JsonNode request = McpSession.JSON
				.readTree(exchange.get("request").toString().replace(FIXTURES, this.fixtures).replace(RUN, this.run));
			String method = request.get("method").stringValue();
			ObjectNode java = this.session.request(method, request.get("params"));
			if ("initialize".equals(method)) {
				this.session.notify("notifications/initialized");
			}
			if (this.run.isEmpty() && "simulate".equals(request.at("/params/name").stringValue(""))) {
				this.run = java.at("/result/structuredContent/runId").stringValue("");
			}
			JsonNode python = withJavasEngine(exchange.get("answer"));
			String text = placeheld(java.toString(), exchange.get("request").toString());
			this.pricedAtZero.learn(request.at("/params/arguments/document"), python, McpSession.JSON.readTree(text));
			JsonNode answered = this.pricedAtZero.asPython(text);
			AnswerComparison.assertAnsweredAs(python, answered, request);
		}

		// the answer with the files' folder named by its placeholder, and a file
		// named as pathlib names it, with the system's separators, named by its
		// placeholder too, as the recorder named it
		private String placeheld(String answer, String request) {
			String text = answer;
			for (String name : NAMED_FILE.matcher(request).results().map(MatchResult::group).distinct().toList()) {
				String named = Path.of(name.replace(FIXTURES, this.fixtures)).toString();
				text = text.replace(inJson(PythonText.repr(named)), inJson(PythonText.repr(name)));
			}
			return text.replace(this.fixtures, FIXTURES);
		}

		// text as a JSON string holds it
		private static String inJson(String text) {
			String quoted = McpSession.JSON.writeValueAsString(text);
			return quoted.substring(1, quoted.length() - 1);
		}

		// Python's answer as Java's names its engine
		private static JsonNode withJavasEngine(JsonNode python) {
			String text = python.toString()
				.replace("\"engineVersion\":\"0.16.0\"", "\"engineVersion\":\"" + EngineVersions.ENGINE + "\"")
				.replace("\\\"engineVersion\\\": \\\"0.16.0\\\"",
						"\\\"engineVersion\\\": \\\"" + EngineVersions.ENGINE + "\\\"");
			return McpSession.JSON.readTree(text);
		}

	}

}
