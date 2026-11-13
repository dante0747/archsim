package io.archsim.benchmarks.docker;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each container's CPU and memory read through the window k6 measures, on the test's
 * clock: the load starts at once, the window opens after k6's warm-up and readings come
 * every five seconds until a second before its end.
 */
class ResourceSamplerTests {

	private static final double START = 1000.0;

	// each container uses 0.4 cores and holds 50 MiB
	private static String counters(FakeTicker ticker) {
		return "usage_usec " + (long) ((ticker.now() - START) * 400_000) + "\nanon 41943040\n52428800\n";
	}

	// at 5, 10, 15 and 20 s after the start, and a second before the window's end
	@Test
	void start_aLoadThatStarted_readsEveryFiveSecondsThroughTheMeasuredWindow(@TempDir Path folder) {
		ObjectNode result = sampled(folder, 1).orElseThrow();

		assertThat(result.get("readings").intValue()).isEqualTo(5);
	}

	@Test
	void start_aLoadThatStarted_spansTheWindowLessASecond(@TempDir Path folder) {
		ObjectNode result = sampled(folder, 1).orElseThrow();

		assertThat(result.get("seconds").doubleValue()).isEqualTo(19.0);
	}

	@Test
	void start_aLoadThatStarted_isEachContainersCpuAndMemory(@TempDir Path folder) {
		ObjectNode result = sampled(folder, 1).orElseThrow();

		assertThat(result.get("containers")).isEqualTo(JsonText.read("""
				{"api": {"cores": 0.4, "maxCores": 0.4, "workingSetMiB": 50.0, "anonMiB": 40.0},
				 "postgres": {"cores": 0.4, "maxCores": 0.4, "workingSetMiB": 50.0, "anonMiB": 40.0}}"""));
	}

	// a calibration pass, which k6 records from its start: from 0 s to a second before 30
	@Test
	void start_aCalibrationPass_readsFromTheStartOfTheLoad(@TempDir Path folder) {
		ObjectNode result = sampled(folder, 1, ResourceSampler.Window.CALIBRATION).orElseThrow();

		assertThat(result.get("seconds").doubleValue()).isEqualTo(29.0);
	}

	@Test
	void start_noLoad_readsNothing(@TempDir Path folder) {
		assertThat(sampled(folder, 0)).isEmpty();
	}

	// Docker gone after each container's first reading: the sampling ends, as run.py's
	// thread did, and says why
	@Test
	void close_aReadingThatFailed_saysWhy(@TempDir Path folder) {
		ResourceSampler sampler = failingAfterOneTick(folder);

		sampler.close();

		assertThat(sampler.failure()).hasValueSatisfying((why) -> assertThat(why).contains("docker is gone"));
	}

	@Test
	void close_aReadingThatFailed_keepsWhatWasReadBeforeIt(@TempDir Path folder) {
		ResourceSampler sampler = failingAfterOneTick(folder);

		sampler.close();

		assertThat(sampler.result().orElseThrow().get("readings").intValue()).isOne();
	}

	@Test
	void close_aReadingThatRanItsCourse_hasNoFailure(@TempDir Path folder) {
		FakeTicker ticker = new FakeTicker(START);
		ResourceSampler sampler = sampler(
				instruments(folder, ticker, new FakeDocker((command) -> answer(command, ticker)), 1),
				ResourceSampler.Window.MEASURED);

		sampler.close();

		assertThat(sampler.failure()).isEmpty();
	}

	// the containers read through the measured window while the service reports so many
	// requests
	private static Optional<ObjectNode> sampled(Path folder, int requests) {
		return sampled(folder, requests, ResourceSampler.Window.MEASURED);
	}

	private static Optional<ObjectNode> sampled(Path folder, int requests, ResourceSampler.Window window) {
		FakeTicker ticker = new FakeTicker(START);
		try (ResourceSampler sampler = sampler(
				instruments(folder, ticker, new FakeDocker((command) -> answer(command, ticker)), requests), window)) {
			return sampler.result();
		}
	}

	// each container's counters read once, then Docker failing
	private static ResourceSampler failingAfterOneTick(Path folder) {
		FakeTicker ticker = new FakeTicker(START);
		AtomicInteger reads = new AtomicInteger();
		FakeDocker docker = new FakeDocker((command) -> {
			if (command.startsWith("docker exec ") && reads.incrementAndGet() > 2) {
				throw new UncheckedIOException(new IOException("docker is gone"));
			}
			return answer(command, ticker);
		});
		return sampler(instruments(folder, ticker, docker, 1), ResourceSampler.Window.MEASURED);
	}

	// a sampler that has read the whole window, on the caller's thread
	private static ResourceSampler sampler(Instruments instruments, ResourceSampler.Window window) {
		return ResourceSampler.start(instruments, List.of("api", "postgres"), window, Runnable::run);
	}

	// a stack on Docker as the test plays it, its service reporting so many requests
	private static Instruments instruments(Path folder, FakeTicker ticker, FakeDocker docker, int requests) {
		HarnessLog log = new HarnessLog(new PrintWriter(new StringWriter()),
				Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
		Compose compose = Compose.of(docker, folder.resolve("compose.yaml"), Map.of(), log);
		return new Instruments(compose, new Cgroups(compose, folder, ticker),
				new FakeServices((request) -> JsonText.read("{\"requests\": " + requests + "}")), ticker);
	}

	// each container's id, then its counters
	private static ProcessResult answer(String command, FakeTicker ticker) {
		if (command.endsWith("ps -q api") || command.endsWith("ps -q postgres")) {
			return FakeDocker.printing("id-" + command.substring(command.lastIndexOf(' ') + 1) + "\n");
		}
		return FakeDocker.printing(counters(ticker));
	}

}
