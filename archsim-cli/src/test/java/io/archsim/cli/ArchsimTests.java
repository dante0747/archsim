package io.archsim.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.archsim.engine.version.ArchsimVersion;
import io.archsim.engine.version.EngineVersions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code archsim} command itself: what it says it is, and how it answers a command it
 * does not have or one that cannot go on.
 */
class ArchsimTests {

	@TempDir
	private Path folder;

	// ADR-0076: ArchSim's version, then the engine's
	@ParameterizedTest
	@ValueSource(strings = { "--version", "validate --version", "run --version", "calibrate otlp --version" })
	void execute_versionOption_printsArchsimsVersionAndTheEngines(String arguments) {
		StringWriter out = new StringWriter();

		int exitCode = Archsim.commandLine().setOut(new PrintWriter(out)).execute(arguments.split(" "));

		assertThat(List.of(exitCode, out.toString().strip())).containsExactly(0,
				"archsim " + ArchsimVersion.PRODUCT + ", engine " + EngineVersions.ENGINE);
	}

	// ADR-0073: the command only the Python harness called went with it
	@Test
	void execute_functions_isNoCommand() {
		ArchsimRun run = ArchsimRun.of("functions");

		assertThat(run.exitCode()).isEqualTo(2);
		assertThat(run.err()).contains("'functions'");
	}

	@Test
	void execute_aCommandRefused_printsWhyWithoutAStackTrace() {
		Path missing = this.folder.resolve("missing.json");
		StringWriter out = new StringWriter();
		StringWriter err = new StringWriter();

		int exitCode = Archsim.commandLine()
			.setOut(new PrintWriter(out))
			.setErr(new PrintWriter(err))
			.execute("validate", missing.toString());

		assertThat(List.of(exitCode, out.toString(), err.toString().strip())).containsExactly(1, "",
				"Cannot read %s: there is no such file or folder".formatted(missing));
	}

}
