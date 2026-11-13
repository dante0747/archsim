package io.archsim.benchmarks.calibration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.docker.Cgroups;
import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.FakeDocker;
import io.archsim.benchmarks.docker.FakeServices;
import io.archsim.benchmarks.docker.FakeTicker;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.load.K6;
import io.archsim.benchmarks.values.PythonValues;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The calibration's passes on a stack a test plays: containers holding 42 MiB whose CPU
 * rises a millisecond a second, a service counting its requests, k6 writing its summary.
 * The sampler reads on the caller's thread, so each pass is read through its whole
 * window.
 */
class DockerPassesTests {

	private static final Experiment PG_MIXED = Experiments.standard().get("pg-mixed");

	private final List<String> commands = Collections.synchronizedList(new ArrayList<>());

	private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

	private final Logs logs = new Logs();

	@Test
	void idle_eachContainer_recordsItsWorkingSet(@TempDir Path folder) {
		JsonNode idle = passes(folder, "").idle(PG_MIXED);

		assertThat(idle.get("workingSetMiB")).isEqualTo(JsonText.read("{\"api\": 42.0, \"postgres\": 42.0}"));
	}

	// one millisecond of CPU a second at rest
	@Test
	void idle_eachContainer_recordsItsCpuAtRest(@TempDir Path folder) {
		JsonNode idle = passes(folder, "").idle(PG_MIXED);

		assertThat(idle.get("containerCpuMsPerSecond")).isEqualTo(JsonText.read("{\"api\": 1.0, \"postgres\": 1.0}"));
	}

	@Test
	void idle_theServicesOwnCounter_isItsCpuAtRest(@TempDir Path folder) {
		JsonNode idle = passes(folder, "").idle(PG_MIXED);

		assertThat(idle.get("serviceCpuMsPerSecond").doubleValue()).isEqualTo(1.5);
	}

	@Test
	void settle_aStack_vacuumsCheckpointsAndWarmsUp(@TempDir Path folder) {
		JsonNode settled = passes(folder, "").settle(PG_MIXED);

		assertThat(settled.get("steps"))
			.isEqualTo(JsonText.read("[\"VACUUM ANALYZE\", \"CHECKPOINT\", \"30 s warm-up at 50 req/s\"]"));
		assertThat(this.commands).anyMatch((command) -> command.endsWith("-c VACUUM ANALYZE -c CHECKPOINT"))
			.anyMatch((command) -> command.contains("-e RATE=50 ") && command.contains("-e OUT=settle "));
	}

	@Test
	void run_aPass_recordsItsFieldsInRunPysOrder(@TempDir Path folder) {
		JsonNode entry = passes(folder, "").run(PG_MIXED, PG_MIXED.mix(), PythonValues.integer(400), "up 2");

		assertThat(entry.propertyNames()).containsExactly("mix", "rateRps", "k6", "service", "containerCpuMs",
				"resources");
	}

	// what k6 recorded from its start, read through the whole 30 s
	@Test
	void run_aPass_takesItsResourcesFromTheSampler(@TempDir Path folder) {
		JsonNode entry = passes(folder, "").run(PG_MIXED, PG_MIXED.mix(), PythonValues.integer(400), "up 2");

		assertThat(entry.at("/resources/seconds").doubleValue()).isEqualTo(29.0);
	}

	@Test
	void run_aCounterNotRead_isNone(@TempDir Path folder) {
		JsonNode entry = passes(folder, "postgres").run(PG_MIXED, PG_MIXED.mix(), PythonValues.integer(400), "up 2");

		assertThat(entry.at("/containerCpuMs/postgres").isNull()).isTrue();
	}

	@Test
	void run_theCacheStack_resetsRedisAndReadsItsStatistics(@TempDir Path folder) {
		Experiment cacheAside = Experiments.standard().get("cache-aside");

		JsonNode entry = passes(folder, "").run(cacheAside, cacheAside.mix(), PythonValues.integer(400), "up 2");

		assertThat(this.commands).anyMatch((command) -> command.endsWith("exec -T redis redis-cli CONFIG RESETSTAT"));
		assertThat(entry.has("redis")).isTrue();
	}

	@Test
	void run_kafka_resetsTheConsumerAndReadsIt(@TempDir Path folder) {
		Experiment kafka = Experiments.standard().get("kafka");

		JsonNode entry = passes(folder, "").run(kafka, kafka.mix(), PythonValues.integer(400), "up 2");

		assertThat(this.requests).contains("POST http://127.0.0.1:18081/reset");
		assertThat(entry.get("consumer")).isEqualTo(JsonText.read("{\"consumed\": 0}"));
	}

	// Docker gone while the sampler reads: the pass is still recorded, and the log says
	// why
	@Test
	void run_aSamplerThatFailed_saysWhy(@TempDir Path folder) {
		DockerPasses passes = passes(folder, (command) -> command.endsWith("/sys/fs/cgroup/memory.current"), "");

		passes.run(PG_MIXED, PG_MIXED.mix(), PythonValues.integer(400), "up 2");

		assertThat(this.logs.text().lines()).contains("[12:34:56]   reading the containers failed: "
				+ "a container's counters could not be read: java.io.IOException: docker is gone");
	}

	@Test
	void run_aSamplerThatFailed_recordsThePassWithoutItsResources(@TempDir Path folder) {
		DockerPasses passes = passes(folder, (command) -> command.endsWith("/sys/fs/cgroup/memory.current"), "");

		JsonNode entry = passes.run(PG_MIXED, PG_MIXED.mix(), PythonValues.integer(400), "up 2");

		assertThat(entry.has("resources")).isFalse();
	}

	// a stack whose container's CPU counter cannot be read for the one named
	private DockerPasses passes(Path folder, String unread) {
		return passes(folder, (command) -> false, unread);
	}

	// the same, Docker failing the commands named
	private DockerPasses passes(Path folder, Predicate<String> failing, String unread) {
		FakeTicker ticker = new FakeTicker(0);
		Path summaries = folder.resolve(".k6");
		FakeDocker docker = new FakeDocker((command) -> {
			this.commands.add(command);
			if (failing.test(command)) {
				throw new UncheckedIOException(new IOException("docker is gone"));
			}
			return FakeDocker.printing(answer(command, ticker, summaries, unread));
		});
		Compose compose = Compose.of(docker, folder.resolve("compose.yaml"), Map.of(), this.logs.log());
		Instruments stack = new Instruments(compose, new Cgroups(compose, folder, ticker),
				new FakeServices((request) -> {
					this.requests.add(request);
					return request.contains(":18081/") ? JsonText.read("{\"consumed\": 0}")
							: JsonText.read("{\"requests\": 1, \"cpuMs\": 30.0}");
				}), ticker);
		return new DockerPasses(stack, new K6(compose, summaries), Runnable::run, this.logs.log());
	}

	// a container's id, its counters, or k6's summary written where the harness reads it
	private static String answer(String command, FakeTicker ticker, Path summaries, String unread) {
		if (command.contains(" ps -q ")) {
			return "id-" + command.substring(command.lastIndexOf(' ') + 1);
		}
		if (command.contains(" k6 run ")) {
			write(summaries, command.substring(command.indexOf("OUT=") + "OUT=".length(), command.indexOf(" k6 run")));
			return "";
		}
		if (!command.startsWith("docker exec ") || command.contains("id-" + unread + " ")) {
			return "";
		}
		return "usage_usec " + (long) (ticker.now() * 1000) + "\nanon 41943040\n44040192\n";
	}

	private static void write(Path summaries, String name) {
		try {
			Files.createDirectories(summaries);
			Files.writeString(summaries.resolve(name + ".json"), "{}");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
