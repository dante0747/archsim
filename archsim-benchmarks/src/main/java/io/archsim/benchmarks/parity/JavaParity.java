package io.archsim.benchmarks.parity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.results.KneePairs;
import io.archsim.benchmarks.results.ResultFiles;

/**
 * The Java engine's parity page from its inputs: {@code simulation-parity.json}, both
 * engines' development checks and, once measured, both engines' speed.
 */
public final class JavaParity {

	/**
	 * The Java engine's speed, from the repository's root: where the speed task writes
	 * it.
	 */
	static final String JAVA_SPEED = "benchmarks/perf/java-port.json";

	/** {@code simulation-parity.json}, from the repository's root. */
	private static final String PARITY = "docs/validation/simulation-parity.json";

	/** The development checks, from the repository's root. */
	private static final String DEV = "benchmarks/results/dev";

	/** The frozen engine's speed, from the repository's root. */
	private static final String PYTHON_SPEED = "benchmarks/perf/python-" + KneePairs.FROZEN_ENGINE + ".json";

	private static final String RESTORE = "restore it with git checkout HEAD -- ";

	private final Path root;

	/**
	 * The page from the inputs in a repository.
	 * @param root the repository's root
	 */
	public JavaParity(Path root) {
		this.root = root;
	}

	/**
	 * The inputs not there yet, each with the command that writes it, or for the two
	 * engines' knees and the frozen engine's speed the one that restores it: no engine
	 * writes those again. Speed is optional while neither engine's is measured, never
	 * with one engine's and not the other's.
	 * @return a line per missing input
	 */
	public List<String> missing() {
		Map<Path, String> needed = new LinkedHashMap<>();
		needed.put(dev().resolve(KneePairs.PYTHON_KNEES), RESTORE + DEV + "/" + KneePairs.PYTHON_KNEES);
		needed.put(dev().resolve(KneePairs.JAVA_KNEES), RESTORE + DEV + "/" + KneePairs.JAVA_KNEES);
		needed.put(parity(), "run " + JavaParityPage.PARITY_COMMAND);
		if (speedStarted()) {
			needed.put(pythonSpeed(), RESTORE + PYTHON_SPEED);
			needed.put(javaSpeed(), "run " + JavaParityPage.JAVA_SPEED_COMMAND);
		}
		List<String> missing = new ArrayList<>();
		needed.forEach((path, remedy) -> {
			if (!Files.exists(path)) {
				missing.add(path.getFileName() + " is missing: " + remedy);
			}
		});
		return missing;
	}

	/**
	 * The page, from inputs that are all there ({@link #missing()}).
	 * @return the page's text
	 * @throws IllegalStateException when {@code simulation-parity.json} is missing, or
	 * one engine's speed is there and the other's is not (missing checks give fewer rows)
	 */
	public String page() {
		Speed speed = speedStarted() ? Speed.of(SpeedRows.of(read(pythonSpeed()), read(javaSpeed())))
				: Speed.notMeasured();
		return JavaParityPage.render(read(parity()), KneePairs.of(dev()), speed);
	}

	/**
	 * What to run when the committed page is not the one its inputs give.
	 * @return the command, in words
	 */
	public static String command() {
		return JavaParityPage.COMMAND;
	}

	// either engine's speed measured means both must be
	private boolean speedStarted() {
		return Files.exists(pythonSpeed()) || Files.exists(javaSpeed());
	}

	private static JsonNode read(Path path) {
		return ResultFiles.read(path).orElseThrow(() -> new IllegalStateException(path + " is missing"));
	}

	private Path parity() {
		return this.root.resolve(PARITY);
	}

	private Path dev() {
		return this.root.resolve(DEV);
	}

	private Path pythonSpeed() {
		return this.root.resolve(PYTHON_SPEED);
	}

	private Path javaSpeed() {
		return this.root.resolve(JAVA_SPEED);
	}

}
