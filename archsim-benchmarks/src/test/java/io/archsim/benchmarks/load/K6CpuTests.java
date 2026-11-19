package io.archsim.benchmarks.load;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.docker.Cgroups;
import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.FakeDocker;
import io.archsim.benchmarks.docker.FakeServices;
import io.archsim.benchmarks.docker.FakeTicker;
import io.archsim.benchmarks.docker.Instruments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * k6's own CPU, watched on the test's own thread: its container using half a core, on a
 * stack whose service has counted a request, so the load has started.
 */
class K6CpuTests {

	private static final String COUNTING = "{\"requests\": 1}";

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

	// the service counts no request for two minutes: k6 is still starting its virtual
	// users, or failed
	@Test
	void cores_aLoadThatNeverStarts_isNone(@TempDir Path folder) {
		FakeTicker ticker = new FakeTicker(0);
		Instruments stack = stack(running(new ArrayList<>(), ticker), ticker, folder, "{\"requests\": 0}");

		try (K6Cpu watch = K6Cpu.watch(stack, new K6.Window(0, 20), Runnable::run)) {
			assertThat(watch.cores()).isEmpty();
		}
	}

	// k6's run ended before the watch had begun
	@Test
	void cores_aWatchStoppedBeforeItReads_isNone(@TempDir Path folder) {
		FakeTicker ticker = new FakeTicker(0);
		List<Runnable> deferred = new ArrayList<>();
		K6Cpu watch = K6Cpu.watch(stack(running(new ArrayList<>(), ticker), ticker, folder, COUNTING),
				new K6.Window(0, 20), deferred::add);

		watch.stop();
		deferred.getFirst().run();

		assertThat(watch.cores()).isEmpty();
	}

	// from the load's start: after the 5 s warm-up and 2 s more, and 2 s before the 20 s
	// window ends
	@Test
	void cores_aRunWithAWarmUp_isReadInsideTheMeasuredWindow(@TempDir Path folder) {
		FakeTicker ticker = new FakeTicker(0);
		List<Double> readAt = new ArrayList<>();

		watched(running(readAt, ticker), ticker, folder, new K6.Window(5, 20));

		assertThat(readAt).containsExactly(7.0, 23.0);
	}

	// k6's container found at once, each reading of its counter kept with its time
	private static FakeDocker running(List<Double> readAt, FakeTicker ticker) {
		return new FakeDocker((command) -> {
			if (command.contains(" ps -q ")) {
				return FakeDocker.printing("k6-run-id\n");
			}
			readAt.add(ticker.now());
			return FakeDocker.printing("usage_usec 0\n");
		});
	}

	private static K6Cpu watched(FakeDocker docker, FakeTicker ticker, Path folder) {
		return watched(docker, ticker, folder, new K6.Window(0, 20));
	}

	private static K6Cpu watched(FakeDocker docker, FakeTicker ticker, Path folder, K6.Window window) {
		try (K6Cpu watch = K6Cpu.watch(stack(docker, ticker, folder, COUNTING), window, Runnable::run)) {
			return watch;
		}
	}

	// a stack whose service answers its statistics so
	private static Instruments stack(FakeDocker docker, FakeTicker ticker, Path folder, String statistics) {
		Compose compose = Compose.of(docker, folder.resolve("compose.yaml"), Map.of(), new Logs().log());
		return new Instruments(compose, new Cgroups(compose, folder, ticker),
				new FakeServices((request) -> JsonText.read(statistics)), ticker);
	}

}
