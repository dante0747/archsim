package io.archsim.benchmarks;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stack's command: a held-out experiment it would run stops it before Docker starts,
 * and an experiment it does not know is refused.
 */
class StackCommandTests {

	// the Java engine is not frozen yet: go-mixed stays sealed
	@Test
	void execute_aSealedExperimentsStack_stopsBeforeDockerSayingWhy(@TempDir Path root) throws IOException {
		FakeMachine machine = FakeMachine.smoking(root, Map.of(), 200);
		StringWriter err = new StringWriter();

		int exitCode = machine.commandLine(root, new StringWriter(), err).execute("go-mixed");

		assertThat(exitCode).isEqualTo(1);
		assertThat(err.toString()).startsWith("go-mixed is a held-out experiment (registered 2026-10-30");
		assertThat(machine.docker().commands()).isEmpty();
	}

	@Test
	void execute_onlyAnUnknownExperiment_isRefused(@TempDir Path root) throws IOException {
		FakeMachine machine = FakeMachine.smoking(root, Map.of(), 200);
		StringWriter err = new StringWriter();

		int exitCode = machine.commandLine(root, new StringWriter(), err).execute("postgres", "--only", "nope");

		assertThat(exitCode).isEqualTo(2);
		assertThat(err.toString()).contains("--only: no experiment 'nope' (one of pg-mixed, ");
	}

}
