package io.archsim.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.DynamicTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.text.PythonJson;
import io.archsim.parity.JsonDiff;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The frozen engine's CLI as {@code scripts/export_cli_cases.py} recorded it, one file
 * per command ({@code <command>-cases.json.gz}), and each case run again in Java: its
 * files written to a folder, its arguments given their paths, the text printed given back
 * the placeholders the recording names the files by, with Python's line ends.
 */
final class RecordedCases {

	/**
	 * The indent of the JSON Python's CLI printed: {@code json.dumps(value, indent=2)}.
	 */
	static final int PYTHON_INDENT = 2;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** The design's file in the recorded arguments and text. */
	private static final String DESIGN = "{design}";

	/** The report's file. */
	private static final String REPORT = "{report}";

	/** A file that does not exist. */
	private static final String MISSING = "{missing}";

	private RecordedCases() {
	}

	/**
	 * Every recorded case of a command.
	 * @param command the command, such as {@code validate}
	 * @return its cases, in the recording's order
	 */
	static List<JsonNode> of(String command) {
		String name = command + "-cases.json.gz";
		try (InputStream compressed = Objects.requireNonNull(RecordedCases.class.getResourceAsStream(name), name);
				InputStream json = new GZIPInputStream(compressed)) {
			List<JsonNode> cases = new ArrayList<>();
			JSON.readTree(json).get("cases").forEach(cases::add);
			return cases;
		}
		catch (IOException ex) {
			throw new IllegalStateException("cannot read " + name, ex);
		}
	}

	/**
	 * Each recorded case of a command run in Java, as a test: the same exit code and the
	 * same text on standard error and output, word for word, but for JSON printed, which
	 * holds the same values as the golden files compare them ({@link JsonDiff}: numbers
	 * to a relative 1e-9, an integer and a float alike, keys in any order). A case where
	 * Python crashed (its {@code crash}) is left out: the Java CLI's answer is a
	 * departure, tested with the command.
	 * @param command the command, such as {@code validate}
	 * @param folder where the cases' files are written
	 * @return a test for each case
	 */
	static Stream<DynamicTest> replays(String command, Path folder) {
		List<JsonNode> cases = of(command).stream().filter((recorded) -> !recorded.has("crash")).toList();
		Stream<DynamicTest> text = cases.stream()
			.filter((recorded) -> !printsJson(recorded))
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				Ran java = run(recorded, folder);

				assertThat(List.of(java.exitCode(), java.out(), java.err())).containsExactly(
						recorded.get("exitCode").intValue(), recorded.get("stdout").stringValue(),
						recorded.get("stderr").stringValue());
			}));
		Stream<DynamicTest> json = cases.stream()
			.filter(RecordedCases::printsJson)
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				Ran java = run(recorded, folder);

				JsonNode printed = JSON.readTree(java.out());
				assertThat(List.of(java.exitCode(), java.err())).containsExactly(recorded.get("exitCode").intValue(),
						recorded.get("stderr").stringValue());
				assertThat(JsonDiff.differences(JSON.readTree(recorded.get("stdout").stringValue()), printed))
					.isEmpty();
				assertThat(java.out()).isEqualTo(PythonJson.writeIndented(printed, PYTHON_INDENT) + "\n");
			}));
		return Stream.concat(text, json);
	}

	/**
	 * Each recorded case of a command whose file Python could not read (missing, or not
	 * JSON: it crashed, exit code 1), run in Java, as a test: the same exit code and
	 * standard output, and a one-line refusal on standard error that names the file.
	 * @param command the command, such as {@code validate}
	 * @param folder where the cases' files are written
	 * @return a test for each case
	 */
	static Stream<DynamicTest> unreadableFiles(String command, Path folder) {
		return of(command).stream()
			.filter((recorded) -> recorded.path("crash").stringValue("").startsWith("JSONDecodeError")
					|| recorded.path("crash").stringValue("").startsWith("FileNotFoundError"))
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				Ran java = run(recorded, folder);

				assertThat(List.of(java.exitCode(), java.out())).containsExactly(recorded.get("exitCode").intValue(),
						recorded.get("stdout").stringValue());
				assertThat(java.err().lines()).singleElement()
					.asString()
					.matches("Cannot read \\{(design|missing)\\}: .+");
			}));
	}

	/**
	 * A recorded case by its name.
	 * @param command the command, such as {@code analyze}
	 * @param name the case's name
	 * @return the case
	 */
	static JsonNode named(String command, String name) {
		return of(command).stream()
			.filter((recorded) -> recorded.get("name").stringValue().equals(name))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("no case " + name + " of " + command));
	}

	private static boolean printsJson(JsonNode recorded) {
		String out = recorded.get("stdout").stringValue();
		return out.startsWith("{") || out.startsWith("[");
	}

	/**
	 * Runs a recorded case in Java.
	 * @param recorded the case
	 * @param folder where its files are written
	 * @return what the command did
	 * @throws IOException when a file cannot be written
	 */
	static Ran run(JsonNode recorded, Path folder) throws IOException {
		String name = recorded.get("name").stringValue();
		Map<String, Path> files = new LinkedHashMap<>();
		files.put(REPORT, folder.resolve(name + "-report.json"));
		files.put(DESIGN, folder.resolve(name + ".json"));
		files.put(MISSING, folder.resolve("missing.json"));
		if (recorded.has("design")) {
			Files.writeString(files.get(DESIGN), recorded.get("design").toString());
		}
		else if (recorded.has("text")) {
			Files.writeString(files.get(DESIGN), recorded.get("text").stringValue(), StandardCharsets.UTF_8);
		}
		List<String> args = new ArrayList<>();
		recorded.get("args")
			.forEach((arg) -> args.add(files.containsKey(arg.stringValue()) ? files.get(arg.stringValue()).toString()
					: arg.stringValue()));
		StringWriter out = new StringWriter();
		StringWriter err = new StringWriter();
		int exitCode = Archsim.commandLine()
			.setOut(new PrintWriter(out))
			.setErr(new PrintWriter(err))
			.execute(args.toArray(String[]::new));
		return new Ran(exitCode, placeheld(out.toString(), files), placeheld(err.toString(), files), files.get(REPORT));
	}

	// the text with the files' paths as the recording names them, and Python's line ends
	private static String placeheld(String text, Map<String, Path> files) {
		String placeheld = text.replace("\r\n", "\n");
		for (Map.Entry<String, Path> file : files.entrySet()) {
			placeheld = placeheld.replace(file.getValue().toString(), file.getKey());
		}
		return placeheld;
	}

	/**
	 * What a run of a command did.
	 *
	 * @param exitCode its exit code
	 * @param out what it printed on standard output
	 * @param err what it printed on standard error
	 * @param report where it was asked to write the report
	 */
	record Ran(int exitCode, String out, String err, Path report) {

	}

}
