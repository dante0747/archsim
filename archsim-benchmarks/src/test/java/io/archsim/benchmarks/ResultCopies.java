package io.archsim.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import io.archsim.benchmarks.results.KeptRun;

/**
 * Copies of the committed results for a test to work on, never the results themselves.
 */
public final class ResultCopies {

	private static final String COMPARISON = KeptRun.COMPARISON + ".json";

	private ResultCopies() {
	}

	/**
	 * Copies a folder of results.
	 * @param from the committed folder
	 * @param to where the copy goes
	 * @throws IOException when a file cannot be copied
	 */
	public static void copy(Path from, Path to) throws IOException {
		try (Stream<Path> files = Files.walk(from)) {
			for (Path file : files.toList()) {
				Path copy = to.resolve(from.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(copy);
				}
				else {
					Files.copy(file, copy);
				}
			}
		}
	}

	/**
	 * Copies a folder of results without its comparisons, which the test computes again.
	 * @param from the committed folder
	 * @param to where the copy goes
	 * @throws IOException when a file cannot be copied
	 */
	public static void copyWithoutComparisons(Path from, Path to) throws IOException {
		try (Stream<Path> files = Files.walk(from)) {
			for (Path file : files.toList()) {
				Path copy = to.resolve(from.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(copy);
				}
				else if (!file.getFileName().toString().equals(COMPARISON)) {
					Files.copy(file, copy);
				}
			}
		}
	}

	/**
	 * Copies an experiment's latest run, without the runs kept before it.
	 * @param from the experiment's committed folder
	 * @param to where the copy goes
	 * @throws IOException when a file cannot be copied
	 */
	public static void copyLatest(Path from, Path to) throws IOException {
		Files.createDirectories(to);
		try (Stream<Path> files = Files.list(from)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				Files.copy(file, to.resolve(file.getFileName().toString()));
			}
		}
	}

	/**
	 * The comparisons under a folder.
	 * @param folder the folder
	 * @return each {@code comparison.json} in it, at any depth
	 * @throws IOException when the folder cannot be read
	 */
	public static List<Path> comparisons(Path folder) throws IOException {
		try (Stream<Path> files = Files.walk(folder)) {
			return files.filter((file) -> file.getFileName().toString().equals(COMPARISON)).toList();
		}
	}

}
