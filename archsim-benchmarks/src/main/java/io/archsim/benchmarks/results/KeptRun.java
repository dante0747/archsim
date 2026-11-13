package io.archsim.benchmarks.results;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One kept run of an experiment: its results folder, {@code run-N/} for an older run or
 * the experiment's own folder for the latest.
 *
 * @param experiment the experiment's name
 * @param directory the run's folder
 */
public record KeptRun(String experiment, Path directory) {

	/** What the run measured: {@code measured.json}, which makes a folder a run. */
	public static final String MEASURED = "measured";

	/** What the engine predicted before the run measured: {@code predictions.json}. */
	public static final String PREDICTIONS = "predictions";

	/** The measurements against the predictions: {@code comparison.json}. */
	public static final String COMPARISON = "comparison";

	/**
	 * CPU and memory simulated again later, for a run whose predictions recorded none:
	 * {@code resources-predicted.json}.
	 */
	public static final String LATER_RESOURCES = "resources-predicted";

	/**
	 * Whether a folder holds a run: its {@code measured.json}.
	 * @param folder the folder
	 * @return whether it is a run
	 */
	public static boolean isRun(Path folder) {
		return Files.exists(folder.resolve(MEASURED + ".json"));
	}

	/**
	 * A file of the run's.
	 * @param name its name, {@link #COMPARISON} for {@code comparison.json}
	 * @return its path
	 */
	public Path file(String name) {
		return this.directory.resolve(name + ".json");
	}

}
