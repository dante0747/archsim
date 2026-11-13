package io.archsim.benchmarks.results;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;

/**
 * Every run kept for each experiment, in the experiments' order and oldest first:
 * {@code run-1/}, {@code run-2/}, ... then the latest, the experiment's own folder. A
 * folder is a run once it holds {@code measured.json}.
 */
public final class KeptRuns {

	private static final String OLDER = "run-";

	private KeptRuns() {
	}

	/**
	 * The kept runs under a results folder.
	 * @param results the folder, {@code benchmarks/results/}
	 * @return the runs
	 */
	public static List<KeptRun> of(Path results) {
		List<KeptRun> runs = new ArrayList<>();
		for (Experiment experiment : Experiments.standard().all()) {
			Path latest = results.resolve(experiment.id());
			List<Path> folders = new ArrayList<>(older(latest));
			folders.add(latest);
			folders.stream().filter(KeptRun::isRun).forEach((folder) -> runs.add(new KeptRun(experiment.id(), folder)));
		}
		return runs;
	}

	private static List<Path> older(Path latest) {
		if (!Files.isDirectory(latest)) {
			return List.of();
		}
		try (Stream<Path> children = Files.list(latest)) {
			return children.filter(Files::isDirectory)
				.filter((folder) -> isOlderRun(folder.getFileName().toString()))
				.sorted(Comparator.comparingInt(KeptRuns::number))
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static boolean isOlderRun(String name) {
		return name.startsWith(OLDER) && name.length() > OLDER.length()
				&& name.substring(OLDER.length()).chars().allMatch(Character::isDigit);
	}

	private static int number(Path folder) {
		return Integer.parseInt(folder.getFileName().toString().substring(OLDER.length()));
	}

}
