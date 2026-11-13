package io.archsim.benchmarks.docker;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.archsim.benchmarks.Logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * {@code docker compose} with the benchmark's stacks: a failing command shows what it
 * printed before it stops the harness.
 */
class ComposeTests {

	private static final Path FILE = Path.of("benchmarks", "compose.yaml");

	@Test
	void output_aCommand_runsComposeWithTheStacksFile() {
		FakeDocker docker = FakeDocker.silent();

		Compose.of(docker, FILE, Map.of(), new Logs().log()).output("ps", "-q", "api");

		assertThat(docker.commands()).containsExactly("docker compose -f " + FILE + " ps -q api");
	}

	// set empty, as run.py set it, unless the harness's environment names a cache
	@Test
	void output_noCacheNamed_setsTheCachesAddressEmpty() {
		FakeDocker docker = FakeDocker.silent();

		Compose.of(docker, FILE, Map.of(), new Logs().log()).output("ps");

		assertThat(docker.environments()).containsExactly(Map.of("BENCH_REDIS_URL", ""));
	}

	@Test
	void with_aStacksVariables_setsThemOverTheOthers() {
		FakeDocker docker = FakeDocker.silent();

		Compose.of(docker, FILE, Map.of(), new Logs().log()).with(Map.of("BENCH_ITEMS", "100000")).output("ps");

		assertThat(docker.environments()).containsExactly(Map.of("BENCH_REDIS_URL", "", "BENCH_ITEMS", "100000"));
	}

	@Test
	void output_aFailingCommand_logsWhatItPrintedAndIsRefused() {
		Logs logs = new Logs();
		Compose compose = Compose.of(failing(), FILE, Map.of(), logs.log());

		Throwable thrown = catchThrowable(() -> compose.output("up", "-d", "--build", "--wait", "--profile", "x"));

		assertThat(logs.text().lines()).containsExactly(
				"[12:34:56] docker compose up -d --build --wait failed (exit 1):", "building", "no space left");
		assertThat(thrown).isInstanceOf(IllegalStateException.class)
			.hasMessage("docker compose up -d --build --wait --profile x failed with exit code 1");
	}

	@Test
	void container_aServiceNotRunning_isNone() {
		Compose compose = Compose.of(FakeDocker.silent(), FILE, Map.of(), new Logs().log());

		assertThat(compose.container("api")).isEmpty();
	}

	@Test
	void container_aRunningService_isItsId() {
		Compose compose = Compose.of(new FakeDocker((command) -> FakeDocker.printing("3f2a\n")), FILE, Map.of(),
				new Logs().log());

		assertThat(compose.container("api")).contains("3f2a");
	}

	private static FakeDocker failing() {
		return new FakeDocker((command) -> new ProcessResult(1, "building", "no space left"));
	}

}
