package io.archsim.benchmarks.resimulation;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A kept run checked with today's model, against the check the Python harness wrote with
 * the Java engine before it was 1.0.0 ({@code results/dev/0.16.0-java/}): the same
 * inputs, knee, steps and comparison; only what names the check (when, the engine, the
 * provenance, each step's result digest) and the words that name the harness differ.
 */
class DevCheckTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final List<String> NAMES = List.of("at", "engineVersion", "provenance");

	private static final JsonNode PROVENANCE = JsonNodeFactory.instance.objectNode().put("machineId", "test");

	private static final DevCheck CHECKS = new DevCheck(LAYOUT.results(), Simulations.onTheEngine(),
			new CheckStamp("1.0.0", PROVENANCE, Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC)));

	/** Cache-ttl's latest run checked, once for the tests of what names a check. */
	private static ObjectNode cacheTtl = JsonNodeFactory.instance.objectNode();

	@BeforeAll
	static void checkCacheTtl() {
		cacheTtl = CHECKS.of(new KeptRun("cache-ttl", LAYOUT.results().resolve("cache-ttl"))).orElseThrow();
	}

	@ParameterizedTest(name = "{0} {1}")
	@CsvSource({ "pg-mixed, pg-mixed/run-5, 2026-10-28T23-21-36Z", "cache-ttl, cache-ttl, 2026-11-04T06-11-13Z" })
	void of_aKeptRun_isTheRecordedCheckButForWhatNamesIt(String experiment, String results, String calibratedAt) {
		JsonNode recorded = ResultFiles
			.read(LAYOUT.results().resolve("dev/0.16.0-java").resolve(experiment).resolve(calibratedAt + ".json"))
			.orElseThrow();

		ObjectNode check = CHECKS.of(new KeptRun(experiment, LAYOUT.results().resolve(results))).orElseThrow();

		assertThat(ResultFiles.text(withoutWhatNamesIt(check)))
			.isEqualTo(ResultFiles.text(withoutWhatNamesIt(recorded)));
	}

	@Test
	void of_aKeptRun_isNamedByItsTimeEngineAndProvenance() {
		assertThat(cacheTtl.properties()).filteredOn((field) -> NAMES.contains(field.getKey()))
			.extracting(Map.Entry::getValue)
			.containsExactly(JsonNodeFactory.instance.stringNode("2026-11-13T10:00:00Z"),
					JsonNodeFactory.instance.stringNode("1.0.0"), PROVENANCE);
	}

	@Test
	void of_aKeptRun_saysTheHarnessDerivedItsInputsAgain() {
		assertThat(cacheTtl.get("how").stringValue()).startsWith(
				"Inputs derived again from this run's calibration passes with today's harness, and its measured");
	}

	// run 1 was calibrated from one pass, not a low and a top one
	@Test
	void of_aRunCalibratedFromOnePass_isNone() {
		KeptRun run = new KeptRun("pg-mixed", LAYOUT.results().resolve("pg-mixed/run-1"));

		assertThat(CHECKS.of(run)).isEmpty();
	}

	@Test
	void path_aCheck_isNamedByItsCalibration(@TempDir Path results) {
		ObjectNode check = JsonNodeFactory.instance.objectNode()
			.put("calibratedAt", "2026-10-30T10:32:56Z")
			.put("results", "pg-hot-row");

		Path path = DevCheck.path(results, "0.15.0", "pg-hot-row", check);

		assertThat(path).isEqualTo(results.resolve("dev/0.15.0/pg-hot-row/2026-10-30T10-32-56Z.json"));
	}

	static ObjectNode withoutWhatNamesIt(JsonNode check) {
		ObjectNode copy = ((ObjectNode) check).deepCopy();
		copy.remove("at");
		copy.remove("engineVersion");
		copy.remove("provenance");
		copy.remove("how");
		copy.get("steps").forEach((step) -> ((ObjectNode) step).remove("resultDigest"));
		return copy;
	}

}
