package io.archsim.benchmarks.resimulation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.inputs.MissingReadingException;
import io.archsim.benchmarks.inputs.ModelInputs;
import io.archsim.benchmarks.parallel.InParallel;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * A development check of kept runs ({@link DevCheck}), written under {@code results/dev/}
 * in the folder the label names: {@code resimulate}. The runs are checked in parallel, on
 * one simulator, which keeps nothing between runs
 * ({@link io.archsim.engine.simulator.Simulator}), and written in their order; a check
 * writes over the folder's check of the same calibration.
 */
public final class Resimulation {

	private final Path results;

	private final DevCheck checks;

	private final String label;

	private final HarnessLog log;

	/**
	 * Checks of the kept runs under a results folder.
	 * @param results the results folder
	 * @param inputs the inputs' derivation, and the engine's simulations it runs on
	 * @param stamp what names each check
	 * @param log the log, a line per run
	 */
	public Resimulation(Path results, ModelInputs inputs, CheckStamp stamp, HarnessLog log) {
		this.results = results;
		this.checks = new DevCheck(results, inputs, stamp);
		this.label = stamp.label();
		this.log = log;
	}

	/**
	 * Checks some kept runs and writes their checks.
	 * @param runs the runs
	 * @return the files written, in the runs' order; a run without a calibration from two
	 * passes writes none
	 */
	public List<Path> resimulate(List<KeptRun> runs) {
		this.log.line("checking " + runs.size() + " kept runs with engine " + this.label + " (in-sample)");
		List<Optional<ObjectNode>> checked = InParallel.map(runs, this::check);
		List<Path> written = new ArrayList<>();
		for (int index = 0; index < runs.size(); index++) {
			KeptRun run = runs.get(index);
			Optional<ObjectNode> check = checked.get(index);
			if (check.isEmpty()) {
				this.log.line(name(run) + ": skipped, no two-pass calibration");
				continue;
			}
			Path path = DevCheck.path(this.results, this.label, run.experiment(), check.get());
			ResultFiles.write(path, check.get());
			written.add(path);
			this.log.line(name(run) + ": " + summary(check.get()));
		}
		return written;
	}

	private Optional<ObjectNode> check(KeptRun run) {
		try {
			return this.checks.of(run);
		}
		catch (MissingReadingException ex) {
			this.log.line(name(run) + ": today's inputs cannot be derived from it (" + ex.getMessage() + ")");
			return Optional.empty();
		}
	}

	// the knees, measured and simulated, and whether the inputs are those calibrated
	private static String summary(JsonNode check) {
		JsonNode comparison = check.get("comparison");
		return "knee measured " + PythonText.str(PythonValues.get(comparison.get("measuredKnee"), "kneeRps"))
				+ ", simulated " + PythonText.str(PythonValues.get(comparison.get("predictedKnee"), "kneeRps"))
				+ " (error " + PythonText.str(comparison.get("kneeError")) + "); inputs as calibrated: "
				+ PythonText.str(check.get("inputsAsCalibrated"));
	}

	private static String name(KeptRun run) {
		Path folder = run.directory().getFileName();
		return run.experiment() + " (" + ((folder != null) ? folder : run.directory()) + ")";
	}

}
