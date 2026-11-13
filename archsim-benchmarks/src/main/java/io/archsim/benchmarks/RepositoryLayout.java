package io.archsim.benchmarks;

import java.io.File;
import java.nio.file.Path;

/**
 * Where the harness's files are in the repository: the stacks, k6, the recorded results
 * under {@code benchmarks/}, the pages it writes under {@code docs/}.
 *
 * @param root the repository's root
 */
public record RepositoryLayout(Path root) {

	/** The system property the build sets to the repository's root. */
	static final String ROOT_PROPERTY = "archsim.repoRoot";

	public RepositoryLayout {
		root = root.toAbsolutePath().normalize();
	}

	/**
	 * The repository the harness runs in: the root the build names, else the working
	 * directory, where Gradle's {@code run} task starts it.
	 * @return its layout
	 */
	public static RepositoryLayout current() {
		String root = System.getProperty(ROOT_PROPERTY);
		return new RepositoryLayout((root != null && !root.isBlank()) ? Path.of(root) : Path.of(""));
	}

	/**
	 * The harness's data: {@code benchmarks/}.
	 * @return the folder
	 */
	public Path benchmarks() {
		return this.root.resolve("benchmarks");
	}

	/**
	 * The recorded results: {@code benchmarks/results/}.
	 * @return the folder
	 */
	public Path results() {
		return benchmarks().resolve("results");
	}

	/**
	 * A path as the harness's log names it: from the root, with forward slashes.
	 * @param path a path in the repository
	 * @return its name
	 */
	public String name(Path path) {
		return this.root.relativize(path.toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/');
	}

}
