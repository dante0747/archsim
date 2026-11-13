package io.archsim.benchmarks;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the commands {@code run.py} had keep to: none runs while {@code BENCH_ENGINE}
 * names an engine other than Java's, though its help still shows.
 */
class HarnessRulesTests {

	// Python's repr of the value, in its own quotes
	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
			python | 'python'
			Java   | 'Java'
			it's   | "it's"
			""")
	void execute_benchEngineNamingAnotherEngine_isRefusedBeforeAStackStarts(String engine, String repr,
			@TempDir Path root) throws IOException {
		FakeMachine machine = FakeMachine.smoking(root, Map.of("BENCH_ENGINE", engine), 200);
		StringWriter err = new StringWriter();

		int exitCode = machine.commandLine(root, new StringWriter(), err).execute("smoke", "pg-mixed");

		assertThat(exitCode).isEqualTo(2);
		assertThat(err.toString())
			.contains("BENCH_ENGINE=" + repr + ": the harness runs the Java engine only (ADR-0062); unset it");
		assertThat(machine.docker().commands()).isEmpty();
	}

	@Test
	void execute_benchEngineNamingAnotherEngine_refusesAStackBeforeItStarts(@TempDir Path root) throws IOException {
		FakeMachine machine = FakeMachine.smoking(root, Map.of("BENCH_ENGINE", "python"), 200);
		StringWriter err = new StringWriter();

		int exitCode = machine.commandLine(root, new StringWriter(), err).execute("postgres");

		assertThat(exitCode).isEqualTo(2);
		assertThat(err.toString()).startsWith("BENCH_ENGINE='python': the harness runs the Java engine only");
		assertThat(machine.docker().commands()).isEmpty();
	}

	@Test
	void execute_benchEngineNamingAnotherEngine_refusesACommandWithoutDocker(@TempDir Path root) throws IOException {
		FakeMachine machine = FakeMachine.smoking(root, Map.of("BENCH_ENGINE", "python"), 200);
		StringWriter err = new StringWriter();

		int exitCode = machine.commandLine(root, new StringWriter(), err).execute("compare");

		assertThat(exitCode).isEqualTo(2);
		assertThat(err.toString()).startsWith("BENCH_ENGINE='python': the harness runs the Java engine only");
	}

	// report.py and java_parity_report.py never looked at the variable, nor does tree-ids
	@Test
	void execute_benchEngineNamingAnotherEngine_letsTheReportRun(@TempDir Path root) throws IOException {
		FakeMachine machine = FakeMachine.smoking(root, Map.of("BENCH_ENGINE", "python"), 200);
		StringWriter err = new StringWriter();

		machine.commandLine(root, new StringWriter(), err).execute("report");

		assertThat(err.toString()).doesNotContain("BENCH_ENGINE");
	}

	// argparse printed the help before main looked at the variable
	@Test
	void execute_helpWithBenchEngineNamingAnotherEngine_showsTheHelp(@TempDir Path root) throws IOException {
		FakeMachine machine = FakeMachine.smoking(root, Map.of("BENCH_ENGINE", "python"), 200);
		StringWriter out = new StringWriter();

		int exitCode = machine.commandLine(root, out, new StringWriter()).execute("smoke", "--help");

		assertThat(exitCode).isZero();
		assertThat(out.toString()).startsWith("Usage: archsim-benchmarks smoke");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "java" })
	void execute_benchEngineEmptyOrJava_runs(String engine, @TempDir Path root) throws IOException {
		FakeMachine machine = FakeMachine.smoking(root, Map.of("BENCH_ENGINE", engine), 200);

		int exitCode = machine.commandLine(root, new StringWriter(), new StringWriter()).execute("smoke", "pg-mixed");

		assertThat(exitCode).isZero();
	}

	@Test
	void execute_benchEngineUnset_runs(@TempDir Path root) throws IOException {
		FakeMachine machine = FakeMachine.smoking(root, Map.of(), 200);

		int exitCode = machine.commandLine(root, new StringWriter(), new StringWriter()).execute("smoke", "pg-mixed");

		assertThat(exitCode).isZero();
	}

}
