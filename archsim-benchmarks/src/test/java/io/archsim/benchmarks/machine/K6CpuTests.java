package io.archsim.benchmarks.machine;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.FakeDocker;
import io.archsim.benchmarks.docker.FakeTicker;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * k6's own CPU, watched on the test's own thread: its container using half a core.
 */
class K6CpuTests {

	@Test
	void cores_k6sContainer_isItsBusyCoresOverTheMiddleOfTheRun(@TempDir Path folder) {
		FakeTicker ticker = new FakeTicker(0);
		FakeDocker docker = new FakeDocker((command) -> FakeDocker.printing(
				command.contains(" ps -q ") ? "k6-run-id\n" : "usage_usec " + (long) (ticker.now() * 500_000) + "\n"));

		assertThat(watched(docker, ticker, folder).cores()).contains(0.5);
	}

	// k6 never started within 10 s
	@Test
	void cores_noContainer_isNone(@TempDir Path folder) {
		assertThat(watched(FakeDocker.silent(), new FakeTicker(0), folder).cores()).isEmpty();
	}

	@Test
	void cores_aCounterNotRead_isNone(@TempDir Path folder) {
		FakeDocker docker = new FakeDocker(
				(command) -> FakeDocker.printing(command.contains(" ps -q ") ? "k6-run-id\n" : "cat: no such file\n"));

		assertThat(watched(docker, new FakeTicker(0), folder).cores()).isEmpty();
	}

	private static K6Cpu watched(FakeDocker docker, FakeTicker ticker, Path folder) {
		Compose compose = Compose.of(docker, folder.resolve("compose.yaml"), Map.of(), new Logs().log());
		try (K6Cpu watch = K6Cpu.watch(compose, ticker, 20, Runnable::run)) {
			return watch;
		}
	}

}
