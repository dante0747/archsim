package io.archsim.benchmarks.load;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.engine.text.PythonJsonReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * k6's Zipf sampler ({@code benchmarks/k6/load.js}), which spreads the keys of a large
 * keyspace by rejection inversion: Node.js runs it ({@code zipf-check.js}), skipped where
 * there is no {@code node}.
 */
class K6ZipfTests {

	private static final int TIMEOUT_SECONDS = 120;

	/** Every bin within this many standard errors of the exact share. */
	private static final double MAX_STANDARD_ERRORS = 4.5;

	@Test
	void sampleRejectionInversion_aLargeKeyspace_followsTheExactZipfDistribution(@TempDir Path folder)
			throws IOException, InterruptedException, URISyntaxException {
		Path check = Path.of(Objects.requireNonNull(K6ZipfTests.class.getResource("zipf-check.js")).toURI());
		Path load = RepositoryLayout.current().benchmarks().resolve("k6").resolve("load.js");
		Path printed = folder.resolve("output");
		Process node = start(check, load, printed);

		boolean ended = node.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		String output = Files.readString(printed, StandardCharsets.UTF_8);

		assertThat(ended).as(output).isTrue();
		assertThat(node.exitValue()).as(output).isZero();
		JsonNode worst = PythonJsonReader.read(output.strip());
		assertThat(worst.valueStream().map(JsonNode::doubleValue)).hasSize(3)
			.allSatisfy((errors) -> assertThat(errors).isLessThan(MAX_STANDARD_ERRORS));
	}

	// node, its output going to a file so that the deadline holds while it prints nothing
	private static Process start(Path check, Path load, Path printed) throws IOException {
		try {
			return new ProcessBuilder("node", check.toString(), load.toString()).redirectErrorStream(true)
				.redirectOutput(printed.toFile())
				.start();
		}
		catch (IOException ex) {
			assumeTrue(false, "needs Node.js: " + ex.getMessage());
			throw ex;
		}
	}

}
