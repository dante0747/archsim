package io.archsim.benchmarks.range;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The calibrations the range is fitted on: the in-sample experiments' kept runs and
 * trials from protocol v5 on, under the latest kept run's conditions, the discarded
 * trials left out.
 */
class RangeSourcesTests {

	private static final Path RESULTS = RepositoryLayout.current().results();

	// W26's throttled cache-aside trial (1 Nov) carries a DISCARDED file
	@Test
	void of_theCommittedResults_leavesOutTheDiscardedTrial() {
		Set<String> sources = RangeSources.of(RESULTS)
			.stream()
			.map(RangeSources.Source::source)
			.collect(Collectors.toSet());

		assertThat(RESULTS.resolve("trials/2026-11-01-cache-aside-w26-throttled/DISCARDED")).exists();
		assertThat(sources).doesNotContain("trials/2026-11-01-cache-aside-w26-throttled")
			.anyMatch((source) -> source.startsWith("trials/"));
	}

	// they are sealed
	@Test
	void of_theCommittedResults_hasNoHeldOutExperiment() {
		Set<String> experiments = RangeSources.of(RESULTS)
			.stream()
			.map(RangeSources.Source::experiment)
			.collect(Collectors.toSet());

		assertThat(experiments).isSubsetOf(Experiments.standard()
			.all()
			.stream()
			.filter((experiment) -> !experiment.isHeldOut())
			.map(Experiment::id)
			.toList());
	}

	@Test
	void of_aRunUnderOtherConditions_isLeftOut(@TempDir Path results) throws IOException {
		write(results.resolve("pg-mixed/run-1"), "v5", "{\"cpu\": \"x\", \"os\": \"old\"}");
		write(results.resolve("pg-mixed"), "v5", "{\"cpu\": \"x\", \"os\": \"new\"}");

		List<String> sources = RangeSources.of(results).stream().map(RangeSources.Source::source).toList();

		assertThat(sources).containsExactly("pg-mixed");
	}

	@Test
	void of_aRunBeforeProtocolV5_isLeftOut(@TempDir Path results) throws IOException {
		write(results.resolve("pg-mixed/run-1"), "v4", "{\"cpu\": \"x\"}");
		write(results.resolve("pg-mixed"), "v5", "{\"cpu\": \"x\"}");

		List<String> sources = RangeSources.of(results).stream().map(RangeSources.Source::source).toList();

		assertThat(sources).containsExactly("pg-mixed");
	}

	@Test
	void protocolNumber_aProtocolOrAMethodOrNeither_isItsNumberOrOne() {
		assertThat(RangeSources.protocolNumber(JsonText.read("{\"protocol\": \"v5\"}"))).isEqualTo(5);
		assertThat(RangeSources.protocolNumber(JsonText.read("{\"protocol\": \"v6\"}"))).isEqualTo(6);
		assertThat(RangeSources.protocolNumber(JsonText.read("{\"method\": \"two passes (v4)\"}"))).isEqualTo(4);
		assertThat(RangeSources.protocolNumber(JsonText.read("null"))).isEqualTo(1);
	}

	@Test
	void conditions_anEnvironment_isItsMachineOsAndPowerMode(@TempDir Path folder) throws IOException {
		Files.writeString(folder.resolve("environment.json"),
				"{\"cpu\": \"x\", \"os\": \"w\", \"powerMode\": \"Best performance\", \"docker\": \"29\"}");

		assertThat(RangeSources.conditions(folder).toString()).isEqualTo("[\"x\", \"w\", \"Best performance\"]");
	}

	// a kept run: its measurements, its calibration under a protocol, its environment
	private static void write(Path run, String protocol, String environment) throws IOException {
		Files.createDirectories(run);
		Files.writeString(run.resolve("measured.json"), "{\"steps\": []}");
		Files.writeString(run.resolve("calibration.json"),
				"{\"protocol\": \"" + protocol + "\", \"at\": \"2026-11-01T00:00:00Z\"}");
		Files.writeString(run.resolve("environment.json"), environment);
	}

}
