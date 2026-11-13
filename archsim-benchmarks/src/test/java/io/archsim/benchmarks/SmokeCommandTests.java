package io.archsim.benchmarks;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.archsim.benchmarks.docker.FakeServices;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code smoke EXPERIMENT [--keep]}: an experiment's stack checked on Docker, its
 * problems logged, the exit code saying whether it passed.
 */
class SmokeCommandTests {

	@Test
	void execute_withoutAnExperiment_isRefused(@TempDir Path root) throws IOException {
		int exitCode = run(root, FakeMachine.smoking(root, Map.of(), 200), new StringWriter(), "smoke");

		assertThat(exitCode).isEqualTo(2);
	}

	@Test
	void execute_anUnknownExperiment_isRefusedNamingTheExperiments(@TempDir Path root) throws IOException {
		StringWriter err = new StringWriter();

		int exitCode = FakeMachine.smoking(root, Map.of(), 200)
			.commandLine(root, new StringWriter(), err)
			.execute("smoke", "nope");

		assertThat(exitCode).isEqualTo(2);
		assertThat(err.toString()).contains("smoke: no experiment 'nope' (one of pg-mixed, pg-hot-row, ");
	}

	@Test
	void execute_anExperimentThatPasses_saysSoAndExitsWith0(@TempDir Path root) throws IOException {
		StringWriter out = new StringWriter();

		int exitCode = run(root, FakeMachine.smoking(root, Map.of(), 200), out, "smoke", "pg-mixed");

		assertThat(exitCode).isZero();
		assertThat(out.toString().lines()).last().isEqualTo("[12:34:56] smoke pg-mixed: passed");
	}

	@Test
	void execute_anExperimentThatFails_logsEachProblemAndExitsWith1(@TempDir Path root) throws IOException {
		StringWriter out = new StringWriter();

		int exitCode = run(root, FakeMachine.smoking(root, Map.of(), 199), out, "smoke", "pg-mixed");

		assertThat(exitCode).isEqualTo(1);
		assertThat(out.toString().lines()).filteredOn((line) -> line.startsWith("[12:34:56] smoke "))
			.containsExactly("[12:34:56] smoke pg-mixed: the service counted 199 requests, k6 sent 200",
					"[12:34:56] smoke pg-mixed: the service timed 199 handlers, k6 sent 200",
					"[12:34:56] smoke pg-mixed: FAILED");
	}

	@Test
	void execute_keep_leavesTheStackRunning(@TempDir Path root) throws IOException {
		FakeMachine machine = FakeMachine.smoking(root, Map.of(), 200);

		run(root, machine, new StringWriter(), "smoke", "pg-mixed", "--keep");

		assertThat(machine.docker().commands()).last().asString().doesNotContain(" down ");
	}

	@Test
	void execute_aServiceThatNeverAnswers_printsWhyAloneAndExitsWith1(@TempDir Path root) throws IOException {
		FakeServices silent = new FakeServices((request) -> {
			throw new UncheckedIOException(new IOException("connection refused"));
		});
		FakeMachine machine = new FakeMachine(Map.of(), FakeMachine.smoking(root, Map.of(), 200).docker(), silent);
		StringWriter err = new StringWriter();

		int exitCode = machine.commandLine(root, new StringWriter(), err).execute("smoke", "pg-mixed");

		assertThat(exitCode).isEqualTo(1);
		assertThat(err.toString().lines()).containsExactly("http://127.0.0.1:18080/health did not answer within 120 s");
	}

	private static int run(Path root, FakeMachine machine, StringWriter out, String... arguments) {
		return machine.commandLine(root, out, new StringWriter()).execute(arguments);
	}

}
