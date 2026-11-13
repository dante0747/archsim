package io.archsim.benchmarks.campaign;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * An experiment's latest results moved into its next {@code run-N/} before a new run
 * replaces them, so that the report's history keeps every run.
 */
final class Archive {

	private static final Pattern RUN = Pattern.compile("run-(\\d+)");

	private Archive() {
	}

	/**
	 * Moves an experiment's latest results into its next run's folder.
	 * @param results the results folder
	 * @param experiment the experiment's name
	 * @return the folder they went to, numbered after the experiment's kept runs; empty
	 * when there is nothing to keep
	 */
	static Optional<Path> keep(Path results, String experiment) {
		Path base = results.resolve(experiment);
		if (!Files.exists(base.resolve("measured.json"))) {
			return Optional.empty();
		}
		try {
			Path kept = Files.createDirectory(base.resolve("run-" + (lastRun(base) + 1)));
			for (Path file : files(base)) {
				Files.move(file, kept.resolve(file.getFileName()));
			}
			return Optional.of(kept);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// the highest run-N the experiment keeps, 0 for none
	private static int lastRun(Path base) throws IOException {
		try (Stream<Path> folders = Files.list(base)) {
			return folders.filter(Files::isDirectory)
				.map((folder) -> RUN.matcher(String.valueOf(folder.getFileName())))
				.filter(Matcher::matches)
				.mapToInt((run) -> Integer.parseInt(run.group(1)))
				.max()
				.orElse(0);
		}
	}

	// the latest results' files, in order
	private static List<Path> files(Path base) throws IOException {
		try (Stream<Path> files = Files.list(base)) {
			return files.filter(Files::isRegularFile).sorted().toList();
		}
	}

}
