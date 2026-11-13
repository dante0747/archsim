package io.archsim.benchmarks.provenance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.history.GitFixture;
import io.archsim.benchmarks.history.GitRepository;

import static org.assertj.core.api.Assertions.assertThat;

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
