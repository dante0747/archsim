package io.archsim.benchmarks.campaign;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.history.GitFixture;
import io.archsim.benchmarks.history.GitRepository;
import io.archsim.benchmarks.provenance.Provenance;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The held-out experiments' seal: only the Java engine as frozen in {@code engines.json},
 * its tag holding the sources the entry names, with nothing uncommitted and no host
 * machine of this machine's fitting, may calibrate or measure them.
 */
class SealsTests {

	private static final String SOURCES = "a".repeat(64);

	private static final JsonNode CLEAN = JsonText.read("{\"engineSources\": \"" + SOURCES + "\", \"dirty\": false}");

	private static final String FROZEN = """
			[{"version": "1.5.0", "engine": "java", "engineTree": null, "engineSources": "%s",
			  "frozenAt": "2026-11-20", "tag": "engine-1.5.0"}]""".formatted(SOURCES);

	private static final Experiment JAVA_MIXED = Experiments.standard().get("java-mixed");

	@Test
	void of_aHeldOutExperimentNoEngineIsFrozenFor_isSealedAndSaysHowToSmokeIt(@TempDir Path folder) throws IOException {
		String why = seals(folder, "[]", SOURCES).of(JAVA_MIXED, CLEAN).orElseThrow();

		assertThat(why).contains("held-out", "is not frozen")
			.contains("`./gradlew :archsim-benchmarks:run --args=\"smoke java-mixed\"`");
	}

	@Test
	void of_anEngineNoFreezeNames_saysWhatAFreezeRecords(@TempDir Path folder) throws IOException {
		assertThat(seals(folder, "[]", SOURCES).of(JAVA_MIXED, CLEAN)).hasValueSatisfying((why) -> assertThat(why)
			.contains("the engine as it is (sources aaaaaaaaaaaa) is not frozen in benchmarks/engines.json")
			.contains("--args=\"engine-id\"` prints what a freeze records"));
	}

	// the entry names the sources that would run, and its tag holds them
	@Test
	void of_aHeldOutExperimentOnTheFrozenEngine_isNotSealed(@TempDir Path folder) throws IOException {
		assertThat(seals(folder, FROZEN, SOURCES).of(JAVA_MIXED, CLEAN)).isEmpty();
	}

	@Test
	void of_anEngineWhoseSourcesChangedSinceItsFreeze_isSealedAndNamesThem(@TempDir Path folder) throws IOException {
		JsonNode changed = JsonText.read("{\"engineSources\": \"" + "b".repeat(64) + "\", \"dirty\": false}");

		assertThat(seals(folder, FROZEN, SOURCES).of(JAVA_MIXED, changed))
			.hasValueSatisfying((why) -> assertThat(why).contains("sources bbbbbbbbbbbb) is not frozen"));
	}

	// an entry alone, written into engines.json without its tag, opens nothing
	@Test
	void of_aFreezeWhoseTagIsNotInTheClone_isSealedAndSaysToFetchIt(@TempDir Path folder) throws IOException {
		Seals seals = Seals.of(engines(folder, FROZEN), (tag) -> Optional.empty(), false);

		assertThat(seals.of(JAVA_MIXED, CLEAN)).hasValueSatisfying((why) -> assertThat(why)
			.contains("its freeze names tag engine-1.5.0, which is not in this clone or holds other sources")
			.contains("`git fetch --tags`"));
	}

	@Test
	void of_aFreezeWhoseTagHoldsOtherSources_isSealed(@TempDir Path folder) throws IOException {
		assertThat(seals(folder, FROZEN, "c".repeat(64)).of(JAVA_MIXED, CLEAN))
			.hasValueSatisfying((why) -> assertThat(why).contains("holds other sources than the entry says"));
	}

	// uncommitted changes, or git could not tell
	@ParameterizedTest
	@ValueSource(strings = { "true", "null" })
	void of_theFrozenEngineWithUncommittedChanges_isSealed(String dirty, @TempDir Path folder) throws IOException {
		JsonNode provenance = JsonText.read("{\"engineSources\": \"" + SOURCES + "\", \"dirty\": " + dirty + "}");

		assertThat(seals(folder, FROZEN, SOURCES).of(JAVA_MIXED, provenance))
			.hasValueSatisfying((why) -> assertThat(why).contains("uncommitted"));
	}

	// BENCH_HOST=on: the designs' host machine comes from this machine's checks
	@Test
	void of_theFrozenEngineOnAHostMachine_isSealedAndSaysToUnsetIt(@TempDir Path folder) throws IOException {
		Seals onHostMachine = Seals.of(engines(folder, FROZEN), (tag) -> Optional.of(SOURCES), true);

		assertThat(onHostMachine.of(JAVA_MIXED, CLEAN))
			.hasValueSatisfying((why) -> assertThat(why).contains("BENCH_HOST=on").contains("Unset it."));
	}

	@ParameterizedTest
	@ValueSource(strings = { "pg-mixed", "pg-hot-row", "cache-aside", "kafka" })
	void of_anExperimentTunedOn_isNeverSealed(String experiment, @TempDir Path folder) throws IOException {
		JsonNode anything = JsonText.read("{\"engineSources\": null, \"dirty\": true}");

		assertThat(seals(folder, "[]", SOURCES).of(Experiments.standard().get(experiment), anything)).isEmpty();
	}

	// a repository whose tag engine-1.5.0 holds the three source trees the entry names
	@Test
	void of_aFreezeWhoseTagTheRepositoryHolds_isNotSealed(@TempDir Path folder) throws IOException {
		GitFixture fixture = GitFixture.init(folder);
		fixture.commit(Map.of("archsim-engine/Engine.java", "class Engine {}\n", "archsim-model/Model.java",
				"class Model {}\n", "archsim-cli/Cli.java", "class Cli {}\n"));
		fixture.tag("engine-1.5.0");
		GitRepository git = new GitRepository(folder);
		String sources = Provenance.engineSources(git, "refs/tags/engine-1.5.0").orElseThrow();
		JsonNode provenance = JsonText.read("{\"engineSources\": \"" + sources + "\", \"dirty\": false}");

		Seals seals = Seals.of(engines(folder, FROZEN.replace(SOURCES, sources)), git, false);

		assertThat(seals.of(JAVA_MIXED, provenance)).isEmpty();
	}

	// a branch can be given any name: only a tag is a freeze
	@Test
	void of_aBranchNamedAsTheFreezesTag_leavesItSealed(@TempDir Path folder) throws IOException {
		GitFixture fixture = GitFixture.init(folder);
		fixture.commit(Map.of("archsim-engine/Engine.java", "class Engine {}\n", "archsim-model/Model.java",
				"class Model {}\n", "archsim-cli/Cli.java", "class Cli {}\n"));
		fixture.branch("engine-1.5.0");
		GitRepository git = new GitRepository(folder);
		String sources = Provenance.engineSources(git, "engine-1.5.0").orElseThrow();
		JsonNode provenance = JsonText.read("{\"engineSources\": \"" + sources + "\", \"dirty\": false}");

		Seals seals = Seals.of(engines(folder, FROZEN.replace(SOURCES, sources)), git, false);

		assertThat(seals.of(JAVA_MIXED, provenance))
			.hasValueSatisfying((why) -> assertThat(why).contains("which is not in this clone"));
	}

	// engines.json today: no Java engine is frozen
	@Test
	void of_theCommittedEngines_sealsTheHeldOutExperiments() {
		RepositoryLayout layout = RepositoryLayout.current();

		Seals seals = Seals.of(layout.engines(), new GitRepository(layout.root()), false);

		assertThat(seals.of(JAVA_MIXED, CLEAN)).isPresent();
	}

	// seals whose every tag holds these sources
	private static Seals seals(Path folder, String engines, String atEveryTag) throws IOException {
		return Seals.of(engines(folder, engines), (tag) -> Optional.of(atEveryTag), false);
	}

	private static Path engines(Path folder, String engines) throws IOException {
		return Files.writeString(folder.resolve("engines.json"), engines);
	}

}
