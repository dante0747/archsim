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

	/** The calibration the predictions came from: {@code calibration.json}. */
	public static final String CALIBRATION = "calibration";

	/** The machine and the stack's pinning: {@code environment.json}. */
	public static final String ENVIRONMENT = "environment";

	/**
	 * CPU and memory simulated again later, for a run whose predictions recorded none:
	 * {@code resources-predicted.json}.
	 */
	public static final String LATER_RESOURCES = "resources-predicted";

	/**
	 * Simple estimates of the knee from the calibration alone, to set beside the
	 * prediction: {@code baselines.json}.
	 */
	public static final String BASELINES = "baselines";

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

	/**
	 * The run as the log names it: its experiment and its folder,
	 * {@code pg-mixed (run-3)}.
	 * @return the name
	 */
	public String name() {
		Path folder = this.directory.getFileName();
		return this.experiment + " (" + ((folder != null) ? folder : this.directory) + ")";
	}

}
