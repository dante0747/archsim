package io.archsim.benchmarks.campaign;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The held-out experiments' seal: only an engine frozen in {@code engines.json}, with
 * nothing uncommitted, may calibrate or measure them.
 */
class SealsTests {

	private static final String TREE = "f".repeat(40);

	private static final JsonNode CLEAN = JsonText.read("{\"engineTree\": \"" + TREE + "\", \"dirty\": false}");

	private static final String FROZEN = "[{\"version\": \"0.15.0\", \"engineTree\": \"" + TREE
			+ "\", \"frozenAt\": \"2026-11-05\", \"tag\": \"t\"}]";

	private static final Experiment JAVA_MIXED = Experiments.standard().get("java-mixed");

	@Test
	void of_aHeldOutExperimentNoEngineIsFrozenFor_isSealedAndSaysHowToSmokeIt(@TempDir Path folder) throws IOException {
		String why = seals(folder, "[]").of(JAVA_MIXED, CLEAN).orElseThrow();

		assertThat(why).contains("held-out", "is not frozen")
			.contains("`./gradlew :archsim-benchmarks:run --args=\"smoke java-mixed\"`");
	}

	@Test
	void of_aHeldOutExperimentOnAFrozenEngine_isNotSealed(@TempDir Path folder) throws IOException {
		assertThat(seals(folder, FROZEN).of(JAVA_MIXED, CLEAN)).isEmpty();
	}

	@Test
	void of_anotherEngine_isNotFrozen(@TempDir Path folder) throws IOException {
		JsonNode other = JsonText.read("{\"engineTree\": \"" + "e".repeat(40) + "\", \"dirty\": false}");

		assertThat(seals(folder, FROZEN).of(JAVA_MIXED, other)).hasValueSatisfying((why) -> assertThat(why)
			.contains("this engine (tree eeeeeeeeeeee) is not frozen in benchmarks/engines.json"));
	}

	// uncommitted changes, or git could not tell
	@ParameterizedTest
	@ValueSource(strings = { "true", "null" })
	void of_aFrozenEngineWithUncommittedChanges_isSealed(String dirty, @TempDir Path folder) throws IOException {
		JsonNode provenance = JsonText.read("{\"engineTree\": \"" + TREE + "\", \"dirty\": " + dirty + "}");

		assertThat(seals(folder, FROZEN).of(JAVA_MIXED, provenance))
			.hasValueSatisfying((why) -> assertThat(why).contains("uncommitted"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "pg-mixed", "pg-hot-row", "cache-aside", "kafka" })
	void of_anExperimentTunedOn_isNeverSealed(String experiment, @TempDir Path folder) throws IOException {
		JsonNode anything = JsonText.read("{\"engineTree\": null, \"dirty\": true}");

		assertThat(seals(folder, "[]").of(Experiments.standard().get(experiment), anything)).isEmpty();
	}

	@Test
	void of_theJavaEngine_saysItIsNotFrozenYet(@TempDir Path folder) throws IOException {
		JsonNode javaEngine = JsonText.read("{\"engineSources\": \"" + "a".repeat(64) + "\", \"dirty\": false}");

		assertThat(seals(folder, FROZEN).of(JAVA_MIXED, javaEngine)).hasValueSatisfying((why) -> assertThat(why)
			.contains(": the Java engine is not frozen yet (ADR-0062), so it may not be calibrated"));
	}

	// the 13 Nov 2026 rewrite changed the trees engines.json records
	@Test
	void of_aFrozenEnginesTreeTheRewriteChanged_isKnownByTodaysTree(@TempDir Path folder) throws IOException {
		Files.writeString(folder.resolve("tree-ids.tsv"), "recorded\tcurrent\n" + "d".repeat(40) + "\t" + TREE + "\n");
		String frozen = FROZEN.replace(TREE, "d".repeat(40));

		assertThat(seals(folder, frozen).of(JAVA_MIXED, CLEAN)).isEmpty();
	}

	// engine 0.16.0's tree is frozen; the Java engine's sources are not yet
	@Test
	void of_theCommittedEngines_sealsTheHeldOutExperimentsOnTheJavaEngine() {
		RepositoryLayout layout = RepositoryLayout.current();
		JsonNode javaEngine = JsonText.read("{\"engineSources\": \"" + "a".repeat(64) + "\", \"dirty\": false}");

		assertThat(Seals.of(layout.engines(), layout.treeIds()).of(JAVA_MIXED, javaEngine)).isPresent();
	}

	private static Seals seals(Path folder, String engines) throws IOException {
		return Seals.of(Files.writeString(folder.resolve("engines.json"), engines), folder.resolve("tree-ids.tsv"));
	}

}
