package io.archsim.benchmarks.results;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The kept runs by when each was calibrated. A development check names the folder its run
 * had when the check was made, and a run that was the latest moves to {@code run-N/} when
 * a later one is archived: the calibration's time is what still identifies it.
 */
public final class CalibratedRuns {

	private final Map<String, KeptRun> runs = new HashMap<>();

	private CalibratedRuns(Path results) {
		for (KeptRun run : KeptRuns.of(results)) {
			ResultFiles.read(run.file(KeptRun.CALIBRATION))
				.map((calibration) -> calibration.path("at").stringValue(""))
				.ifPresent((calibratedAt) -> this.runs.put(key(run.experiment(), calibratedAt), run));
		}
	}

	/**
	 * The kept runs under a results folder, each calibration read once.
	 * @param results the folder, {@code benchmarks/results/}
	 * @return the runs by calibration time
	 */
	public static CalibratedRuns of(Path results) {
		return new CalibratedRuns(results);
	}

	/**
	 * The run of an experiment calibrated at a time.
	 * @param experiment the experiment's name
	 * @param calibratedAt the calibration's {@code at}
	 * @return the run; empty when no kept run was calibrated then
	 */
	public Optional<KeptRun> find(String experiment, String calibratedAt) {
		return Optional.ofNullable(this.runs.get(key(experiment, calibratedAt)));
	}

	private static String key(String experiment, String calibratedAt) {
		return experiment + "@" + calibratedAt;
	}

}
