package io.archsim.benchmarks.results;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The results' files as the harness writes them.
 */
class ResultFilesTests {

	@Test
	void write_aFileInAFolderNotThereYet_makesTheFolder(@TempDir Path results) throws IOException {
		Path check = results.resolve("dev/1.0.0/kafka/2026-10-26T12-22-50Z.json");

		ResultFiles.write(check, JsonNodeFactory.instance.objectNode().put("kneeRps", 2000.0));

		assertThat(Files.readString(check, StandardCharsets.UTF_8)).isEqualTo("{\n  \"kneeRps\": 2000.0\n}\n");
	}

}
