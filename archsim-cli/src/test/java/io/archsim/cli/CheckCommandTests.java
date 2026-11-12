package io.archsim.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import io.archsim.engine.version.EngineVersions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code archsim check} against the frozen engine's CLI: each case of
 * {@code check-cases.json.gz} (recorded by {@code scripts/export_cli_cases.py}, since
 * deleted) exits with the code Python's did, 0 for a pass, 1 for a failure and 2 for
 * anything else, and prints, or writes, what it did, word for word, but for the engine it
 * names, its own: against a baseline file, at a git ref, with its breaking point, and
 * each refusal in Python's words.
 */
class CheckCommandTests {

	/** The engine the recording ran, which Python's result names. */
	private static final String PYTHONS_ENGINE = "0.16.0";

	@TestFactory
	Stream<DynamicTest> check_eachRecordedCase_printsWhatThePythonCliPrintedNamingItsOwnEngine(@TempDir Path folder) {
		return Replays.of("check", folder, CheckCommandTests::withJavasEngine);
	}

	@TestFactory
	Stream<DynamicTest> check_eachUsageErrorRecorded_exitsWithCode2(@TempDir Path folder) {
		return Replays.usageErrors("check", folder);
	}

	@Test
	void check_help_namesTheDefaultFormatAsItIsTyped() {
		String usage = Objects.requireNonNull(Archsim.commandLine().getSubcommands().get("check")).getUsageMessage();

		assertThat(usage).contains("one of: markdown, json (default: markdown)");
	}

	@Test
	void check_anErrorOfTheJvmsEndingIt_isOneLineWithExitCode2NotAStackTrace() {
		// as Python answered RecursionError and MemoryError, both Exceptions
		StringWriter err = new StringWriter();
		CommandLine commandLine = Archsim.commandLine().setErr(new PrintWriter(err));
		commandLine.parseArgs("check", "design.json");

		OptionalInt exitCode = Archsim.answeredError(commandLine, new StackOverflowError());

		assertThat(exitCode).hasValue(2);
		assertThat(err.toString().lines())
			.containsExactly("cannot check: unexpected error: java.lang.StackOverflowError");
	}

	@Test
	void anotherCommand_anErrorOfTheJvmsEndingIt_isLeftToTheJvm() {
		CommandLine commandLine = Archsim.commandLine().setErr(new PrintWriter(new StringWriter()));
		commandLine.parseArgs("validate", "design.json");

		assertThat(Archsim.answeredError(commandLine, new StackOverflowError())).isEmpty();
	}

	@Test
	void check_anErrorNobodyExpected_isOneLineWithExitCode2NotAStackTrace(@TempDir Path folder) throws IOException {
		Path design = Files.writeString(folder.resolve("d.json"),
				RecordedCases.named("check", "aDesignMeetingItsObjectives").get("design").toString());
		Checks failing = (document, baseline, request) -> {
			throw new IllegalStateException("something nobody expected");
		};
		StringWriter out = new StringWriter();
		StringWriter err = new StringWriter();

		int exitCode = Archsim.commandLine(InputStream.nullInputStream(), Clock.systemUTC(), failing)
			.setOut(new PrintWriter(out))
			.setErr(new PrintWriter(err))
			.execute("check", design.toString());

		assertThat(List.of(exitCode, out.toString())).containsExactly(2, "");
		assertThat(err.toString().lines()).containsExactly("cannot check: unexpected error: something nobody expected");
	}

	@Test
	void check_theResultOnStandardOutput_endsItsLinesWithALineFeedOnEverySystem(@TempDir Path folder)
			throws IOException {
		// Python wrote it as UTF-8 bytes to sys.stdout.buffer, which no console changes
		Path design = Files.writeString(folder.resolve("d.json"),
				RecordedCases.named("check", "aDesignMeetingItsObjectives").get("design").toString());
		StringWriter out = new StringWriter();

		int exitCode = Archsim.commandLine()
			.setOut(new PrintWriter(out))
			.setErr(new PrintWriter(new StringWriter()))
			.execute("check", design.toString());

		assertThat(exitCode).isZero();
		assertThat(out.toString()).contains("\n").doesNotContain("\r");
	}

	// Python's text as Java's prints it: the result naming the Java engine
	private static String withJavasEngine(String python) {
		return python
			.replace("\"engineVersion\": \"" + PYTHONS_ENGINE + "\"",
					"\"engineVersion\": \"" + EngineVersions.ENGINE + "\"")
			.replace("(engine " + PYTHONS_ENGINE + ", ", "(engine " + EngineVersions.ENGINE + ", ");
	}

}
