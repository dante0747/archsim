package io.archsim.benchmarks.provenance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.history.GitFixture;
import io.archsim.benchmarks.history.GitRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * What produced a prediction or a check: the commit, the engine's and the harness's
 * source trees, whether either had changes, the engine's build and the machine.
 */
class ProvenanceTests {

	private static final String MACHINE = "i7-1165g7";

	@Test
	void of_aRepository_hashesTheJavaEnginesThreeSourceTrees(@TempDir Path folder)
			throws IOException, NoSuchAlgorithmException {
		GitFixture fixture = GitFixture.init(folder);
		String commit = fixture.commit(Map.of("archsim-engine/Engine.java", "class Engine {}\n",
				"archsim-model/Model.java", "class Model {}\n", "archsim-cli/Cli.java", "class Cli {}\n"));
		String trees = "archsim-engine " + fixture.tree(commit, "archsim-engine") + "\narchsim-model "
				+ fixture.tree(commit, "archsim-model") + "\narchsim-cli " + fixture.tree(commit, "archsim-cli");

		JsonNode provenance = new Provenance(new GitRepository(folder), MACHINE, null).of();

		assertThat(provenance.get("engineSources").stringValue()).isEqualTo(sha256(trees));
	}

	// what a freeze records for its tag is what a run from that commit records
	@Test
	void engineSources_aCommit_isTheHashAProvenanceThereRecords(@TempDir Path folder) throws IOException {
		GitFixture fixture = GitFixture.init(folder);
		String commit = fixture.commit(Map.of("archsim-engine/Engine.java", "class Engine {}\n",
				"archsim-model/Model.java", "class Model {}\n", "archsim-cli/Cli.java", "class Cli {}\n"));
		GitRepository git = new GitRepository(folder);

		Optional<String> sources = Provenance.engineSources(git, commit);

		assertThat(sources).contains(new Provenance(git, MACHINE, null).of().get("engineSources").stringValue());
	}

	// a tag this clone lacks, for one
	@Test
	void engineSources_aRevisionGitCannotName_isEmpty(@TempDir Path folder) throws IOException {
		GitFixture fixture = GitFixture.init(folder);
		fixture.commit(Map.of("archsim-engine/Engine.java", "class Engine {}\n"));

		assertThat(Provenance.engineSources(new GitRepository(folder), "engine-9.9.9")).isEmpty();
	}

	// skipped for a tag this clone lacks (CI's checkout fetches none)
	@Test
	void engineSources_eachFrozenJavaEngine_isTheHashOverItsTagsTrees() {
		GitRepository git = new GitRepository(RepositoryLayout.current().root());

		List<JsonNode> frozen = frozenJavaEnginesWithTheirTags(git);

		assertThat(frozen).allSatisfy((engine) -> assertThat(Provenance.engineSources(git, tagOf(engine)))
			.contains(engine.get("engineSources").stringValue()));
	}

	// what a freeze records for the harness is what a run from that commit records
	@Test
	void harnessHash_aCommit_isTheHashAProvenanceThereRecords(@TempDir Path folder) throws IOException {
		GitFixture fixture = GitFixture.init(folder);
		String commit = fixture.commit(
				Map.of("archsim-benchmarks/Harness.java", "class Harness {}\n", "benchmarks/knee-range.json", "{}\n"));
		GitRepository git = new GitRepository(folder);

		Optional<String> harness = Provenance.harnessHash(git, commit);

		assertThat(harness).contains(new Provenance(git, MACHINE, null).of().get("harnessHash").stringValue());
	}

	@Test
	void harnessHash_aRevisionGitCannotName_isEmpty(@TempDir Path folder) throws IOException {
		GitFixture fixture = GitFixture.init(folder);
		fixture.commit(Map.of("archsim-benchmarks/Harness.java", "class Harness {}\n"));

		assertThat(Provenance.harnessHash(new GitRepository(folder), "engine-9.9.9")).isEmpty();
	}

	// the harness a freeze names is its tag's, so a reader can tell whether it has moved
	// since; skipped for a tag this clone lacks
	@Test
	void harnessHash_eachFrozenJavaEngine_isTheHashOverItsTagsHarness() {
		GitRepository git = new GitRepository(RepositoryLayout.current().root());

		List<JsonNode> frozen = frozenJavaEnginesWithTheirTags(git);

		assertThat(frozen).allSatisfy((engine) -> assertThat(Provenance.harnessHash(git, tagOf(engine)))
			.contains(engine.get("harnessHash").stringValue()));
	}

	// the Java engines that engines.json freezes; skipped while there is none, or while
	// this clone lacks a freeze's tag
	private static List<JsonNode> frozenJavaEnginesWithTheirTags(GitRepository git) {
		List<JsonNode> frozen = Engines.read(RepositoryLayout.current().engines())
			.frozen()
			.stream()
			.filter((engine) -> engine.path("engineSources").isString())
			.toList();
		assumeThat(frozen).as("a Java engine is frozen in engines.json").isNotEmpty();
		assumeThat(frozen).as("the frozen engines' tags are in this clone")
			.allSatisfy((engine) -> assertThat(git.revision(tagOf(engine))).isPresent());
		return frozen;
	}

	// a freeze's tag, which a branch of the same name must not stand in for
	private static String tagOf(JsonNode engine) {
		return "refs/tags/" + engine.get("tag").stringValue();
	}

	// a path the commit lacks is named None, as the Python harness's f-string named it
	@Test
	void of_aRepository_hashesTheHarnesssTreesNamingTheMissingOnesNone(@TempDir Path folder)
			throws IOException, NoSuchAlgorithmException {
		GitFixture fixture = GitFixture.init(folder);
		String commit = fixture.commit(Map.of("archsim-benchmarks/Harness.java", "class Harness {}\n"));
		String trees = "archsim-benchmarks " + fixture.tree(commit, "archsim-benchmarks")
				+ "\nbenchmarks/compose.yaml None\nbenchmarks/service None\nbenchmarks/service-java None"
				+ "\nbenchmarks/service-go None\nbenchmarks/service-python None\nbenchmarks/k6 None"
				+ "\nbenchmarks/db None\nbenchmarks/knee-range.json None";

		JsonNode provenance = new Provenance(new GitRepository(folder), MACHINE, null).of();

		assertThat(provenance.get("harnessHash").stringValue()).isEqualTo(sha256(trees));
	}

	@Test
	void of_aChangeToTheHarness_isDirty(@TempDir Path folder) throws IOException {
		GitFixture.init(folder).commit(Map.of("archsim-benchmarks/Harness.java", "class Harness {}\n"));
		Files.writeString(folder.resolve("archsim-benchmarks/Harness.java"), "final class Harness {}\n");

		JsonNode provenance = new Provenance(new GitRepository(folder), MACHINE, null).of();

		assertThat(provenance.get("dirty").booleanValue()).isTrue();
	}

	@Test
	void of_aFolderThatIsNotARepository_namesWhatItCanAndNoneForTheRest(@TempDir Path folder) {
		JsonNode provenance = new Provenance(new GitRepository(folder), MACHINE, "build").of();

		assertThat(provenance.toString()).isEqualTo("{\"commit\":null,\"engineSources\":"
				+ provenance.get("engineSources") + ",\"javaBuild\":\"build\",\"harnessHash\":"
				+ provenance.get("harnessHash") + ",\"dirty\":null,\"machineId\":\"" + MACHINE + "\"}");
	}

	private static String sha256(String text) throws NoSuchAlgorithmException {
		return HexFormat.of()
			.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
	}

}
