package io.archsim.benchmarks;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code envcheck} and {@code contention}: the machine's checks, each written under
 * {@code results/envcheck}.
 */
class MachineCommandsTests {

	@Test
	void execute_envcheck_writesTheCheckAndSaysWhere(@TempDir Path root) throws IOException {
		StringWriter out = new StringWriter();

		int exitCode = FakeMachine.checking(root, Map.of())
			.commandLine(root, out, new StringWriter())
			.execute("envcheck");

		assertThat(exitCode).isZero();
		assertThat(out.toString().lines()).last()
			.isEqualTo("[12:34:56] wrote benchmarks/results/envcheck/2026-11-13T12-34-56Z.json");
		assertThat(Files.exists(root.resolve("benchmarks/results/envcheck/2026-11-13T12-34-56Z.json"))).isTrue();
	}

	// run.py ran neither part and wrote an empty check
	@Test
	void execute_contentionWithBothPartsLeftOut_isRefusedBeforeDockerStarts(@TempDir Path root) throws IOException {
		FakeMachine machine = FakeMachine.checking(root, Map.of());
		StringWriter err = new StringWriter();

		int exitCode = machine.commandLine(root, new StringWriter(), err)
			.execute("contention", "--probe-only", "--service-only");

		assertThat(exitCode).isEqualTo(2);
		assertThat(err.toString())
			.contains("contention: --probe-only and --service-only together leave nothing to check");
		assertThat(machine.docker().commands()).isEmpty();
	}

	@Test
	void execute_envcheckWithAnotherEngine_isRefusedBeforeDockerStarts(@TempDir Path root) throws IOException {
		FakeMachine machine = FakeMachine.checking(root, Map.of("BENCH_ENGINE", "python"));

		int exitCode = machine.commandLine(root, new StringWriter(), new StringWriter()).execute("envcheck");

		assertThat(exitCode).isEqualTo(2);
		assertThat(machine.docker().commands()).isEmpty();
	}

}
