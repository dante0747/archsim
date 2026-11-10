package io.archsim.cli;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.text.PythonText;

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

	/** The recording of a calibration's Prometheus responses ({@code --record}). */
	private static final String RECORD = "{record}";

	/** The stub Prometheus server's base URL. */
	private static final String URL = "{url}";

	/**
	 * The files a calibration reads, by placeholder, named as the recording named them.
	 */
	private static final Map<String, String> INPUT_FILES = Map.of("{map}", "map.yaml", "{recorded}", "recorded.json",
			"{traces}", "traces.json");

	/** The time the recording's calibrations were fitted at. */
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);

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
	 * Runs a recorded case in Java, with the files it reads written, the Prometheus it
	 * queries started, and the clock the recording stopped.
	 * @param recorded the case
	 * @param folder where its files are written
	 * @return what the command did
	 * @throws IOException when a file cannot be written or the stub cannot listen
	 */
	static Ran run(JsonNode recorded, Path folder) throws IOException {
		Map<String, Path> files = files(recorded.get("name").stringValue(), folder);
		writeInputs(recorded, files);
		try (PrometheusStub prometheus = PrometheusStub.behaving(recorded)) {
			Map<String, String> names = new LinkedHashMap<>();
			files.forEach((placeholder, file) -> names.put(placeholder, file.toString()));
			names.put(URL, prometheus.url());
			List<String> args = new ArrayList<>();
			recorded.get("args").forEach((arg) -> args.add(names.getOrDefault(arg.stringValue(), arg.stringValue())));
			StringWriter out = new StringWriter();
			StringWriter err = new StringWriter();
			byte[] standardInput = recorded.path("stdin").stringValue("").getBytes(StandardCharsets.UTF_8);
			int exitCode = Archsim.commandLine(new ByteArrayInputStream(standardInput), CLOCK)
				.setOut(new PrintWriter(out))
				.setErr(new PrintWriter(err))
				.execute(args.toArray(String[]::new));
			Map<String, String> written = new LinkedHashMap<>();
			for (String placeholder : List.of(REPORT, OUT, RECORD)) {
				Path file = files.get(placeholder);
				if (Files.exists(file)) {
					written.put(placeholder, placeheld(Files.readString(file), names));
				}
			}
			return new Ran(exitCode, placeheld(out.toString(), names), placeheld(err.toString(), names), files,
					written);
		}
	}

	// every file a case's arguments may name, as the recording named them
	private static Map<String, Path> files(String name, Path folder) {
		Map<String, Path> files = new LinkedHashMap<>();
		files.put(REPORT, folder.resolve(name + "-report.json"));
		files.put(OUT, folder.resolve(name + "-out.txt"));
		files.put(RECORD, folder.resolve(name + "-record.json"));
		files.put(DESIGN, folder.resolve(name + ".json"));
		files.put(MISSING, folder.resolve("missing.json"));
		INPUT_FILES.forEach((placeholder, suffix) -> files.put(placeholder, folder.resolve(name + "-" + suffix)));
		return files;
	}

	// the design (as JSON, text, or bytes in base64) and the other files the case reads
	private static void writeInputs(JsonNode recorded, Map<String, Path> files) throws IOException {
		Path design = files.get(DESIGN);
		if (recorded.has("design")) {
			Files.writeString(design, recorded.get("design").toString());
		}
		else if (recorded.has("text")) {
			Files.writeString(design, recorded.get("text").stringValue(), StandardCharsets.UTF_8);
		}
		else if (recorded.has("bytes")) {
			Files.write(design, Base64.getDecoder().decode(recorded.get("bytes").stringValue()));
		}
		for (Map.Entry<String, JsonNode> input : recorded.path("inputs").properties()) {
			Files.writeString(files.get(input.getKey()), input.getValue().stringValue(), StandardCharsets.UTF_8);
		}
	}

	// the text with each name the recording placeholds (a file, as it is and as Python's
	// repr writes it; the server's address) given back its placeholder, and Python's line
	// ends
	private static String placeheld(String text, Map<String, String> names) {
		String placeheld = text.replace("\r\n", "\n");
		for (Map.Entry<String, String> name : names.entrySet()) {
			if (name.getValue().isEmpty()) {
				continue;
			}
			String repr = PythonText.repr(name.getValue());
			placeheld = placeheld.replace(repr.substring(1, repr.length() - 1), name.getKey())
				.replace(name.getValue(), name.getKey());
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
	 * @param written the text of each file it wrote (a report, a result, a recording),
	 * the names in it given back their placeholders, by placeholder
	 */
	record Ran(int exitCode, String out, String err, Map<String, Path> files, Map<String, String> written) {

		/**
		 * Copies the files, so the record does not change.
		 */
		Ran {
			files = Map.copyOf(files);
			written = Map.copyOf(written);
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
