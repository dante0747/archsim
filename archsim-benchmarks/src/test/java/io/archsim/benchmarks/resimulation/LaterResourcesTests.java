package io.archsim.benchmarks.resimulation;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CPU and memory of predictions that recorded none, simulated again from the
 * calibration's inputs: on cache-ttl's latest run, the very resources its predictions
 * recorded.
 */
class LaterResourcesTests {

	private static final Path CACHE_TTL = RepositoryLayout.current().results().resolve("cache-ttl");

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-13T12:34:56Z"), ZoneOffset.UTC);

	@Test
	void predict_predictionsWithoutResources_simulatesEachPredictedStepsResources(@TempDir Path results)
			throws IOException {
		JsonNode predictions = copyWithoutResources(results);

		List<Path> written = resources().predict(results);

		JsonNode file = ResultFiles.read(results.resolve("cache-ttl/resources-predicted.json")).orElseThrow();
		assertThat(written).containsExactly(results.resolve("cache-ttl/resources-predicted.json"));
		assertThat(file.get("steps").valueStream().map((step) -> step.get("resources")).toList())
			.isEqualTo(ResultFiles.read(CACHE_TTL.resolve("predictions.json"))
				.orElseThrow()
				.get("steps")
				.valueStream()
				.map((step) -> step.get("resources"))
				.toList());
		assertThat(file.get("steps").valueStream().map((step) -> step.get("offeredRps")).toList())
			.isEqualTo(predictions.get("steps").valueStream().map((step) -> step.get("offeredRps")).toList());
	}

	@Test
	void predict_predictionsWithoutResources_namesTheEngineAndWhen(@TempDir Path results) throws IOException {
		copyWithoutResources(results);

		resources().predict(results);

		JsonNode file = ResultFiles.read(results.resolve("cache-ttl/resources-predicted.json")).orElseThrow();
		assertThat(file.get("experiment").stringValue()).isEqualTo("cache-ttl");
		assertThat(file.get("at").stringValue()).isEqualTo("2026-11-13T12:34:56Z");
		assertThat(file.get("engineVersion").stringValue()).isEqualTo("1.0.0");
	}

	@Test
	void predict_predictionsWithResources_writesNothing(@TempDir Path results) throws IOException {
		Files.createDirectories(results.resolve("cache-ttl"));
		Files.copy(CACHE_TTL.resolve("predictions.json"), results.resolve("cache-ttl/predictions.json"));
		Files.copy(CACHE_TTL.resolve("calibration.json"), results.resolve("cache-ttl/calibration.json"));

		List<Path> written = resources().predict(results);

		assertThat(written).isEmpty();
	}

	private static LaterResources resources() {
		return new LaterResources(Simulations.onTheEngine(), NOON,
				new HarnessLog(new PrintWriter(new StringWriter()), NOON));
	}

	// cache-ttl's latest predictions without their resources, and its calibration
	private static JsonNode copyWithoutResources(Path results) throws IOException {
		Files.createDirectories(results.resolve("cache-ttl"));
		ObjectNode predictions = (ObjectNode) ResultFiles.read(CACHE_TTL.resolve("predictions.json")).orElseThrow();
		predictions.get("steps").forEach((step) -> ((ObjectNode) step).remove("resources"));
		ResultFiles.write(results.resolve("cache-ttl/predictions.json"), predictions);
		Files.copy(CACHE_TTL.resolve("calibration.json"), results.resolve("cache-ttl/calibration.json"));
		return predictions;
	}

}
