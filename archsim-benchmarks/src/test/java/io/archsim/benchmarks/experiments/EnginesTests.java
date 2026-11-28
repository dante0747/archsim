package io.archsim.benchmarks.experiments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.Setups;
import io.archsim.benchmarks.machine.HostSwitch;
import io.archsim.benchmarks.machine.MachineFit;
import io.archsim.engine.version.EngineVersions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engines: their recorded trees, which are frozen and which predictions are held out,
 * from an {@code engines.json} of the tests' own, and the committed one.
 */
class EnginesTests {

	private static final Engines COMMITTED = Engines.read(RepositoryLayout.current().engines());

	/** The Java engine frozen for the second held-out set (ADR-0088, ADR-0090). */
	private static final String FROZEN_JAVA = "1.6.0";

	@Test
	void engineTrees_theEngines_areKeyedByVersionInTheFilesOrder(@TempDir Path folder) throws IOException {
		Path file = write(folder, """
				[{"version": "0.16.0", "engineTree": "b"}, {"version": "0.14.0", "engineTree": "a"}]""");

		Map<String, String> trees = Engines.read(file).engineTrees();

		assertThat(trees).containsExactly(Map.entry("0.16.0", "b"), Map.entry("0.14.0", "a"));
	}

	// the Java engine is not frozen yet, and records no tree
	@Test
	void engineTrees_anEngineWithoutATree_isLeftOut(@TempDir Path folder) throws IOException {
		Path file = write(folder, """
				[{"version": "0.16.0", "engineTree": "b"}, {"version": "1.0.0", "engineTree": null}]""");

		Map<String, String> trees = Engines.read(file).engineTrees();

		assertThat(trees).containsOnlyKeys("0.16.0");
	}

	@Test
	void frozen_theEngines_areTheOnesWithADateATagAndATree(@TempDir Path folder) throws IOException {
		Path file = write(folder, """
				[{"version": "a", "engineTree": "f", "frozenAt": null, "tag": null},
				 {"version": "b", "engineTree": "f", "frozenAt": "2026-11-01", "tag": null},
				 {"version": "c", "engineTree": "f", "frozenAt": "2026-11-01", "tag": "x"}]""");

		List<JsonNode> frozen = Engines.read(file).frozen();

		assertThat(frozen).extracting((engine) -> engine.get("version").stringValue()).containsExactly("c");
	}

	// a Java engine is frozen by the hash over its source trees, where the Python one had
	// a tree
	@Test
	void frozen_aJavaEngineWithADateATagAndItsSources_isFrozen(@TempDir Path folder) throws IOException {
		Path file = write(folder, """
				[{"version": "a", "engineTree": null, "engineSources": "s", "frozenAt": null, "tag": null},
				 {"version": "b", "engineTree": null, "engineSources": null, "frozenAt": "2026-11-20", "tag": "x"},
				 {"version": "c", "engineTree": null, "engineSources": "s", "frozenAt": "2026-11-20", "tag": "x"}]""");

		List<JsonNode> frozen = Engines.read(file).frozen();

		assertThat(frozen).extracting((engine) -> engine.get("version").stringValue()).containsExactly("c");
	}

	@Test
	void frozen_noEnginesFile_isNone(@TempDir Path folder) {
		assertThat(Engines.read(folder.resolve("missing.json")).frozen()).isEmpty();
	}

	// held out: a held-out experiment, an engine frozen here and not tuned on it
	@ParameterizedTest
	@CsvSource({ "0.15.0, pg-mixed, tuned-on", "0.15.0, pg-hot-row, tuned-on", "0.15.0, cache-aside, tuned-on",
			"0.15.0, kafka, tuned-on", "0.15.0, chain, held-out", "0.16.0, chain, tuned-on",
			"0.16.0, go-mixed, held-out", "0.17.0, go-mixed, tuned-on", "9.9.9, go-mixed, tuned-on" })
	void sampleLabel_anEnginesPrediction_isHeldOutOnlyWhereNoEngineSawIt(String version, String experiment,
			String label, @TempDir Path folder) throws IOException {
		Path file = write(folder, """
				[{"version": "0.15.0", "frozenAt": "2026-11-05", "tag": "t",
				  "tunedOn": ["pg-mixed", "pg-hot-row", "cache-aside", "kafka"]},
				 {"version": "0.16.0", "frozenAt": "2026-12-01", "tag": "u", "tunedOn": ["chain"]},
				 {"version": "0.17.0", "frozenAt": null, "tag": null, "tunedOn": []}]""");

		String sample = Engines.read(file)
			.sampleLabel(StringNode.valueOf(version), Experiments.standard().get(experiment));

		assertThat(sample).isEqualTo(label);
	}

	@Test
	void sampleLabel_noEnginesFile_isTunedOn(@TempDir Path folder) {
		String sample = Engines.read(folder.resolve("missing.json"))
			.sampleLabel(StringNode.valueOf("0.14.0"), Experiments.standard().get("chain"));

		assertThat(sample).isEqualTo("tuned-on");
	}

	@Test
	void entries_theCommittedEngines_areTheTwoPythonOnesThenTheJavaOnes() {
		List<JsonNode> engines = COMMITTED.entries();

		assertThat(engines).extracting((engine) -> engine.get("version").stringValue())
			.containsExactly("0.14.0", "0.16.0", "1.5.2", "1.6.0", "1.6.1", EngineVersions.ENGINE);
		assertThat(engines).allSatisfy((engine) -> assertThat(engine.propertyNames()).contains("version", "engineTree",
				"frozenAt", "tag", "tunedOn"));
	}

	// the engines up to 1.5.2 were tuned on the four original experiments, and none on a
	// held-out one
	@Test
	void entries_theCommittedEnginesUpTo152_wereEachTunedOnTheOriginalFour() {
		List<JsonNode> engines = COMMITTED.entries().subList(0, 3);

		assertThat(engines)
			.allSatisfy((engine) -> assertThat(engine.get("tunedOn").valueStream().map(JsonNode::stringValue))
				.containsExactly("pg-mixed", "pg-hot-row", "cache-aside", "kafka"));
	}

	// ADR-0091: 1.6.0 was made with the first held-out set's results in view; the second
	// set, registered before it (ADR-0090), stays held out
	@Test
	void entries_theCommittedEngine160_wasTunedOnTheNineExperimentsMeasured() {
		JsonNode engine = COMMITTED.entries().getLast();

		assertThat(engine.get("tunedOn").valueStream().map(JsonNode::stringValue)).containsExactly("pg-mixed",
				"pg-hot-row", "cache-aside", "kafka", "java-mixed", "go-mixed", "python-mixed", "chain", "cache-ttl");
	}

	// 0.16.0 for the first held-out campaign and as the port's parity reference
	// (ADR-0017), the Java engine 1.6.0 for the second held-out set's (ADR-0088)
	@Test
	void frozen_theCommittedEngines_areEngine0160AndJavaEngine160ByTheirTags() {
		List<JsonNode> frozen = COMMITTED.frozen();

		assertThat(frozen)
			.extracting((engine) -> engine.get("version").stringValue() + " " + engine.get("tag").stringValue())
			.containsExactly("0.16.0 engine-0.16.0", "1.6.0 engine-1.6.0");
	}

	// its sources and the harness then, by the hashes a run's provenance records
	@Test
	void entries_javaEngine160_isFrozenByItsSourcesAndHarness() {
		JsonNode frozen = entry(FROZEN_JAVA);

		assertThat(frozen.get("engine").stringValue()).isEqualTo("java");
		assertThat(List.of(Engines.SOURCES_KEY, "harnessHash"))
			.allSatisfy((key) -> assertThat(frozen.path(key).stringValue("")).as(key).matches("[0-9a-f]{64}"));
		assertThat(COMMITTED.frozen()).contains(frozen);
	}

	// the designs' host machine the committed contention checks fit (ADR-0095). Should it
	// fail, a check committed since the freeze changed the fit: revert it before the
	// campaign, or run the campaign on the frozen fit through BENCH_HOST_FIT
	@Test
	void entries_javaEngine160_namesTheHostMachineTheCommittedChecksFit() {
		MachineFit fit = HostSwitch
			.fit(Map.of("BENCH_MACHINE", Setups.CHECKED_MACHINE), RepositoryLayout.current().results())
			.orElseThrow();

		assertThat(entry(FROZEN_JAVA).path(Engines.HOST_FIT_KEY).stringValue("")).isEqualTo(fit.digest());
	}

	@Test
	void tunedOn_anEngine_isTheExperimentsItLists(@TempDir Path folder) throws IOException {
		Path file = write(folder, """
				[{"version": "1.0.0", "tunedOn": ["pg-mixed", "kafka"]},
				 {"version": "2.0.0", "tunedOn": ["pg-mixed", "kafka", "chain"]}]""");

		assertThat(Engines.read(file).tunedOn("2.0.0")).containsExactly("pg-mixed", "kafka", "chain");
	}

	@Test
	void tunedOn_anEngineNotListed_isNone(@TempDir Path folder) throws IOException {
		Path file = write(folder, """
				[{"version": "1.0.0", "tunedOn": ["pg-mixed"]}]""");

		assertThat(Engines.read(file).tunedOn("9.9.9")).isEmpty();
	}

	private static Path write(Path folder, String text) throws IOException {
		Path file = folder.resolve("engines.json");
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

	// a version's entry in the committed engines.json
	private static JsonNode entry(String version) {
		return COMMITTED.entries()
			.stream()
			.filter((engine) -> version.equals(engine.path("version").stringValue("")))
			.findFirst()
			.orElseThrow();
	}

}
