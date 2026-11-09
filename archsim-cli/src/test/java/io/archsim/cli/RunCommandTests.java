package io.archsim.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.parity.ReportComparison;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code archsim run} against the frozen engine's CLI: each run of
 * {@code run-cases.json.gz} ({@code scripts/export_cli_cases.py}) exits with the code
 * Python's did and prints what it printed, word for word, on standard output and standard
 * error; a report written to a file holds what Python's held, compared as the simulator's
 * recorded runs are ({@link ReportComparison}).
 */
class RunCommandTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String DESIGN = "{design}";

	private static final String REPORT = "{report}";

	@TestFactory
	Stream<DynamicTest> run_eachRecordedRun_printsWhatThePythonCliPrinted(@TempDir Path folder) {
		return recorded().stream().map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
			Ran ran = run(recorded, folder);

			assertThat(List.of(ran.exitCode(), ran.out(), ran.err())).containsExactly(
					recorded.get("exitCode").intValue(), recorded.get("stdout").stringValue(),
					recorded.get("stderr").stringValue());
		}));
	}

	@TestFactory
	Stream<DynamicTest> run_aReportWrittenToAFile_holdsWhatThePythonReportHeld(@TempDir Path folder) {
		return recorded().stream()
			.filter((recorded) -> recorded.has("report"))
			.map((recorded) -> DynamicTest.dynamicTest(recorded.get("name").stringValue(), () -> {
				Ran ran = run(recorded, folder);

				JsonNode written = JSON.readTree(Files.readString(ran.report()));
				assertThat(ReportComparison.differences(recorded.get("report"), written)).isEmpty();
			}));
	}

	private static Ran run(JsonNode recorded, Path folder) throws IOException {
		String name = recorded.get("name").stringValue();
		Path design = folder.resolve(name + ".json");
		Files.writeString(design, recorded.get("design").toString());
		Path report = folder.resolve(name + "-report.json");
		List<String> args = new ArrayList<>();
		recorded.get("args").forEach((arg) -> args.add(switch (arg.stringValue()) {
			case DESIGN -> design.toString();
			case REPORT -> report.toString();
			default -> arg.stringValue();
		}));
		StringWriter out = new StringWriter();
		StringWriter err = new StringWriter();
		int exitCode = Archsim.commandLine()
			.setOut(new PrintWriter(out))
			.setErr(new PrintWriter(err))
			.execute(args.toArray(String[]::new));
		return new Ran(exitCode, placeheld(out.toString(), design, report), placeheld(err.toString(), design, report),
				report);
	}

	// the text with the files' paths as the recording names them, and Python's line ends
	private static String placeheld(String text, Path design, Path report) {
		return text.replace(report.toString(), REPORT).replace(design.toString(), DESIGN).replace("\r\n", "\n");
	}

	private static List<JsonNode> recorded() {
		try (InputStream compressed = Objects
			.requireNonNull(RunCommandTests.class.getResourceAsStream("run-cases.json.gz"), "run-cases.json.gz");
				InputStream json = new GZIPInputStream(compressed)) {
			List<JsonNode> cases = new ArrayList<>();
			JSON.readTree(json).get("cases").forEach(cases::add);
			return cases;
		}
		catch (IOException ex) {
			throw new IllegalStateException("cannot read run-cases.json.gz", ex);
		}
	}

	/**
	 * What a run of the command did.
	 *
	 * @param exitCode its exit code
	 * @param out what it printed on standard output
	 * @param err what it printed on standard error
	 * @param report where it was asked to write the report
	 */
	private record Ran(int exitCode, String out, String err, Path report) {

	}

}
