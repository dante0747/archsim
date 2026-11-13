package io.archsim.benchmarks.docker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.HarnessStopException;
import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.RepositoryLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A stack started afresh and stopped: compose is told the CPUs k6 gets, the cache and the
 * rows to load, and the harness waits until the services answer.
 */
class StackLifecycleTests {

	private static final Cpusets CPUSETS = Cpusets.declaredIn(RepositoryLayout.current().compose());

	@Test
	void items_aStackOfOneMillionKeys_loadsThemAll() {
		assertThat(StackLifecycle.items("cache-ttl")).isEqualTo(1_000_000);
	}

	@ParameterizedTest
	@ValueSource(strings = { "postgres", "cache", "kafka", "java-mixed", "go-mixed", "python-mixed", "chain" })
	void items_aStackOfFewerKeys_loadsTheDefaultRows(String stack) {
		assertThat(StackLifecycle.items(stack)).isEqualTo(StackLifecycle.DEFAULT_ITEMS);
	}

	@Test
	void up_aStack_removesItsLastRunThenStartsIt(@TempDir Path folder) {
		FakeDocker docker = FakeDocker.silent();

		lifecycle(docker, StackLifecycleTests::healthy, folder).up("postgres");

		assertThat(docker.commands()).map((command) -> command.substring(command.indexOf(" --profile")))
			.containsExactly(" --profile postgres down -v --remove-orphans",
					" --profile postgres up -d --build --wait");
	}

	@Test
	void up_theCacheStack_tellsComposeItsCpusCacheAndRows(@TempDir Path folder) {
		FakeDocker docker = FakeDocker.silent();

		lifecycle(docker, StackLifecycleTests::healthy, folder).up("cache");

		assertThat(docker.environments().getLast()).isEqualTo(
				Map.of("BENCH_CPUS_K6", "0,6,7", "BENCH_REDIS_URL", "redis://redis:6379", "BENCH_ITEMS", "100000"));
	}

	// run.py told the service of the cache on the cache stack only
	@Test
	void up_theCacheTtlStack_tellsComposeItsCpusNoCacheAndItsMillionRows(@TempDir Path folder) {
		FakeDocker docker = FakeDocker.silent();

		lifecycle(docker, StackLifecycleTests::healthy, folder).up("cache-ttl");

		assertThat(docker.environments().getLast())
			.isEqualTo(Map.of("BENCH_CPUS_K6", "6,7", "BENCH_REDIS_URL", "", "BENCH_ITEMS", "1000000"));
	}

	@Test
	void up_aServiceThatAnswersLate_waitsForIt(@TempDir Path folder) {
		AtomicInteger asked = new AtomicInteger();
		FakeServices late = new FakeServices((request) -> {
			if (asked.incrementAndGet() < 3) {
				throw new UncheckedIOException(new IOException("connection refused"));
			}
			return healthy(request);
		});

		lifecycle(FakeDocker.silent(), late, folder).up("postgres");

		assertThat(late.requests()).hasSize(3);
	}

	@Test
	void up_aStack_waitsForTheServicesHealth(@TempDir Path folder) {
		FakeServices services = new FakeServices(StackLifecycleTests::healthy);

		lifecycle(FakeDocker.silent(), services, folder).up("postgres");

		assertThat(services.requests()).containsExactly("GET http://127.0.0.1:18080/health");
	}

	@Test
	void up_kafka_waitsForTheConsumerToo(@TempDir Path folder) {
		FakeServices services = new FakeServices(StackLifecycleTests::healthy);

		lifecycle(FakeDocker.silent(), services, folder).up("kafka");

		assertThat(services.requests()).containsExactly("GET http://127.0.0.1:18080/health",
				"GET http://127.0.0.1:18081/health");
	}

	@Test
	void up_aServiceThatNeverAnswers_stopsTheHarness(@TempDir Path folder) {
		FakeServices silent = new FakeServices((request) -> {
			throw new UncheckedIOException(new IOException("connection refused"));
		});
		StackLifecycle lifecycle = lifecycle(FakeDocker.silent(), silent, folder);

		Throwable thrown = catchThrowable(() -> lifecycle.up("postgres"));

		assertThat(thrown).isInstanceOf(HarnessStopException.class)
			.hasMessage("http://127.0.0.1:18080/health did not answer within 120 s");
	}

	@Test
	void down_aStack_removesItsContainersAndVolumes(@TempDir Path folder) {
		FakeDocker docker = FakeDocker.silent();

		lifecycle(docker, StackLifecycleTests::healthy, folder).down("chain");

		assertThat(docker.commands()).singleElement().asString().endsWith(" --profile chain down -v --remove-orphans");
	}

	// a service that answers its health check
	private static JsonNode healthy(String request) {
		return JsonText.read("{\"status\": \"ok\"}");
	}

	private static StackLifecycle lifecycle(FakeDocker docker, Function<String, JsonNode> answers, Path folder) {
		return lifecycle(docker, new FakeServices(answers), folder);
	}

	private static StackLifecycle lifecycle(FakeDocker docker, FakeServices services, Path folder) {
		Logs logs = new Logs();
		FakeTicker ticker = new FakeTicker(0);
		Compose compose = Compose.of(docker, folder.resolve("compose.yaml"), Map.of(), logs.log());
		return new StackLifecycle(
				new Instruments(compose, new Cgroups(compose, Path.of("cgroups"), ticker), services, ticker), CPUSETS,
				Map.of(), logs.log());
	}

}
