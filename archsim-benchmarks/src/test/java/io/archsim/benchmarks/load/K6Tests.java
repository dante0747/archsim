package io.archsim.benchmarks.load;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.FakeDocker;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * k6 in its container: the traffic in its variables, its summary read from the folder it
 * writes to.
 */
class K6Tests {

	private static final Traffic PG_MIXED = Traffic.of(Experiments.standard().get("pg-mixed"), "read:0.9,write:0.1",
			250.0);

	@Test
	void run_traffic_namesItInK6sVariables(@TempDir Path folder) {
		FakeDocker docker = writingSummaries(folder);

		new K6(compose(docker, folder), folder).run(PG_MIXED, new K6.Window(5, 20), "step-pg-mixed-250-1");

		assertThat(docker.commands()).singleElement()
			.asString()
			.endsWith(" --profile load run --rm -e TARGET=http://api:8080 -e MIX=read:0.9,write:0.1 -e RATE=250 "
					+ "-e WARMUP_S=5 -e DURATION_S=20 -e KEYS=100000 -e SKEW=0 -e TIMEOUT_MS=2000 "
					+ "-e VUS=550 -e OUT=step-pg-mixed-250-1 k6 run -q /scripts/load.js");
	}

	@Test
	void run_traffic_isTheSummaryK6Wrote(@TempDir Path folder) {
		JsonNode summary = new K6(compose(writingSummaries(folder), folder), folder).run(PG_MIXED, new K6.Window(5, 20),
				"step");

		assertThat(summary).isEqualTo(JsonText.read("{\"out\": \"step\"}"));
	}

	@Test
	void forget_aSummary_deletesIt(@TempDir Path folder) throws IOException {
		K6 k6 = new K6(compose(FakeDocker.silent(), folder), folder);
		Files.writeString(folder.resolve("smoke.json"), "{}");

		k6.forget("smoke");

		assertThat(folder.resolve("smoke.json")).doesNotExist();
	}

	@Test
	void target_aChainsExperiment_isItsGateway() {
		assertThat(Traffic.target(Experiments.standard().get("chain"))).isEqualTo("http://gateway:8080");
	}

	// the second held-out set: three services of their own, two on the first set's stacks
	@ParameterizedTest
	@CsvSource({ "python-cores, api-python-cores", "python-wide, api-python-wide", "java-hot-row, api-java",
			"go-pool, api-go-pool", "cache-miss, api-ttl" })
	void target_anExperimentOfTheSecondSet_isItsStacksService(String experiment, String service) {
		assertThat(Traffic.target(Experiments.standard().get(experiment))).isEqualTo("http://" + service + ":8080");
	}

	// cache-miss's smoke test: cache-ttl's service and million keys, with no skew
	@Test
	void run_cacheMisssTraffic_drawsItsMillionKeysUniformly(@TempDir Path folder) {
		FakeDocker docker = writingSummaries(folder);
		Experiment cacheMiss = Experiments.standard().get("cache-miss");

		new K6(compose(docker, folder), folder).run(Traffic.of(cacheMiss, cacheMiss.mix(), 20.0), new K6.Window(0, 10),
				"smoke");

		assertThat(docker.commands()).singleElement()
			.asString()
			.contains(" -e TARGET=http://api-ttl:8080 -e MIX=cached:1 -e RATE=20 -e WARMUP_S=0 -e DURATION_S=10 "
					+ "-e KEYS=1000000 -e SKEW=0 -e TIMEOUT_MS=2000 ");
	}

	// every request of java-hot-row's writes its one key
	@Test
	void run_javaHotRowsTraffic_isItsHotMixOverOneKey(@TempDir Path folder) {
		FakeDocker docker = writingSummaries(folder);
		Experiment javaHotRow = Experiments.standard().get("java-hot-row");

		new K6(compose(docker, folder), folder).run(Traffic.of(javaHotRow, javaHotRow.mix(), 20.0),
				new K6.Window(0, 10), "smoke");

		assertThat(docker.commands()).singleElement()
			.asString()
			.contains(" -e TARGET=http://api-java:8080 -e MIX=hot:1 -e RATE=20 -e WARMUP_S=0 -e DURATION_S=10 "
					+ "-e KEYS=1 -e SKEW=0 ");
	}

	// k6 writing its summary, named by OUT, where the harness reads it
	private static FakeDocker writingSummaries(Path folder) {
		return new FakeDocker((command) -> {
			String name = command.substring(command.indexOf("OUT=") + "OUT=".length(), command.indexOf(" k6 run"));
			try {
				Files.writeString(folder.resolve(name + ".json"), "{\"out\": \"" + name + "\"}");
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			return FakeDocker.printing("");
		});
	}

	private static Compose compose(FakeDocker docker, Path folder) {
		return Compose.of(docker, folder.resolve("compose.yaml"), Map.of(), new Logs().log());
	}

}
