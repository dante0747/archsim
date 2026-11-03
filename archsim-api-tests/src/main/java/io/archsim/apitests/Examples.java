package io.archsim.apitests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import tools.jackson.databind.node.ObjectNode;

/**
 * Test inputs from the repository: the example designs and the engine's test fixtures.
 */
public final class Examples {

	/** How long the shortened starter runs, in seconds. */
	private static final int STARTER_DURATION_S = 8;

	/** How long the shortened starter warms up, in seconds. */
	private static final int STARTER_WARMUP_S = 2;

	private Examples() {
	}

	/**
	 * Returns the repository root, as Gradle passes it ({@code archsim.repoRoot}).
	 * @return the root, as an absolute path
	 */
	public static Path repoRoot() {
		return Path.of(System.getProperty("archsim.repoRoot", ".")).toAbsolutePath();
	}

	/**
	 * Loads an example design.
	 * @param id the example's name: the design is {@code examples/<id>.json}
	 * @return a fresh copy of the design, to change at will
	 */
	public static ObjectNode load(String id) {
		return Json.copy(Json.read(repoRoot().resolve("examples").resolve(id + ".json")));
	}

	/**
	 * Loads the cache-aside starter, shortened to 8 s with 2 s of warm-up.
	 * @return a fresh copy of the design
	 */
	public static ObjectNode starter() {
		return starter(STARTER_DURATION_S, STARTER_WARMUP_S);
	}

	/**
	 * Loads the cache-aside starter with the given duration and warm-up.
	 * @param durationS how long the workload runs, in seconds
	 * @param warmupS how long it warms up, in seconds
	 * @return a fresh copy of the design
	 */
	public static ObjectNode starter(int durationS, int warmupS) {
		ObjectNode document = load("cache-aside-starter");
		ObjectNode workload = (ObjectNode) document.get("workload");
		workload.put("durationS", durationS);
		workload.put("warmupS", warmupS);
		return document;
	}

	/**
	 * Returns a file of the engine's test fixtures.
	 * @param relative the file's path under {@code packages/engine/tests/fixtures}
	 * @return the file's path
	 */
	public static Path engineFixture(String relative) {
		return repoRoot().resolve("packages/engine/tests/fixtures").resolve(relative);
	}

	/**
	 * Reads a text file.
	 * @param file the file, in UTF-8
	 * @return the file's text
	 */
	public static String text(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
