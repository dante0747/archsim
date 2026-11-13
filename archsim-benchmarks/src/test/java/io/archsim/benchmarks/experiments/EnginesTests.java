package io.archsim.benchmarks.experiments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engines' recorded trees, from an {@code engines.json} of the tests' own.
 */
class EnginesTests {

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

	private static Path write(Path folder, String text) throws IOException {
		Path file = folder.resolve("engines.json");
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

}
