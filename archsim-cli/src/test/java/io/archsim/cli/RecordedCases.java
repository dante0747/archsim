package io.archsim.cli;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The frozen engine's CLI as {@code scripts/export_cli_cases.py} recorded it, one file
 * per command ({@code <command>-cases.json.gz}), and each case run again in Java: its
 * input written to a file (a design, text or bytes) or given as standard input, its
 * arguments given their files' paths, the text printed given back the placeholders the
 * recording names the files by, with Python's line ends.
 */
final class RecordedCases {

	/**
	 * The indent of the JSON Python's CLI printed: {@code json.dumps(value, indent=2)}.
	 */
	static final int PYTHON_INDENT = 2;

	/**
	 * The design's file, or the file a command reads, in the recorded arguments and text.
	 */
	private static final String DESIGN = "{design}";

	/** The report's file. */
	private static final String REPORT = "{report}";

	/** The file a command writes its result to ({@code -o}). */
	private static final String OUT = "{out}";

	/** A file that does not exist. */
	private static final String MISSING = "{missing}";

	private static final JsonMapper JSON = JsonMapper.builder().build();

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
		files.put(OUT, folder.resolve(name + "-out.txt"));
		files.put(DESIGN, folder.resolve(name + ".json"));
		files.put(MISSING, folder.resolve("missing.json"));
		writeInput(recorded, files.get(DESIGN));
		List<String> args = new ArrayList<>();
		recorded.get("args")
			.forEach((arg) -> args.add(files.containsKey(arg.stringValue()) ? files.get(arg.stringValue()).toString()
					: arg.stringValue()));
		StringWriter out = new StringWriter();
		StringWriter err = new StringWriter();
		byte[] standardInput = recorded.path("stdin").stringValue("").getBytes(StandardCharsets.UTF_8);
		int exitCode = Archsim.commandLine(new ByteArrayInputStream(standardInput))
			.setOut(new PrintWriter(out))
			.setErr(new PrintWriter(err))
			.execute(args.toArray(String[]::new));
		return new Ran(exitCode, placeheld(out.toString(), files), placeheld(err.toString(), files), files);
	}

	// the file the command reads: a design as JSON, text, or bytes in base64
	private static void writeInput(JsonNode recorded, Path file) throws IOException {
		if (recorded.has("design")) {
			Files.writeString(file, recorded.get("design").toString());
		}
		else if (recorded.has("text")) {
			Files.writeString(file, recorded.get("text").stringValue(), StandardCharsets.UTF_8);
		}
		else if (recorded.has("bytes")) {
			Files.write(file, Base64.getDecoder().decode(recorded.get("bytes").stringValue()));
		}
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
	 * @param files the files its arguments could name, by placeholder
	 */
	record Ran(int exitCode, String out, String err, Map<String, Path> files) {

		/**
		 * Copies the files, so the record does not change.
		 */
		Ran {
			files = Map.copyOf(files);
		}

		/**
		 * Where the command was asked to write a report.
		 * @return the report's file
		 */
		Path report() {
			return Objects.requireNonNull(this.files.get(REPORT), REPORT);
		}

	}

}
