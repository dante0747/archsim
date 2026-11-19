package io.archsim.benchmarks.baselines;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.model.text.PythonText;

/**
 * The kept runs' {@code baselines.json}, each beside its run's {@code comparison.json}:
 * {@code baselines}. A file is written where there is none or where today's harness and
 * engine give other figures; one whose figures are today's stays as it is, with the stamp
 * of what first found them, so that running the command again changes nothing. No
 * recorded file is touched.
 */
public final class BaselineFiles {

	private final Baselines baselines;

	private final HarnessLog log;

	/**
	 * Files of some baselines.
	 * @param baselines the kept runs' baselines
	 * @param log the log, a line per run
	 */
	public BaselineFiles(Baselines baselines, HarnessLog log) {
		this.baselines = baselines;
		this.log = log;
	}

	/**
	 * Writes the baselines of the kept runs that have none, or whose file holds other
	 * figures than today's.
	 * @param runs the runs
	 * @return the files written, in the runs' order; a run without a calibration has none
	 */
	public List<Path> write(List<KeptRun> runs) {
		return writeWhere(runs, (recorded, today) -> !Baselines.sameFigures(recorded, today));
	}

	/**
	 * Writes every kept run's baselines again, stamped anew, though a file's figures are
	 * today's.
	 * @param runs the runs
	 * @return the files written, in the runs' order; a run without a calibration has none
	 */
	public List<Path> rewrite(List<KeptRun> runs) {
		return writeWhere(runs, (recorded, today) -> true);
	}

	private List<Path> writeWhere(List<KeptRun> runs, BiPredicate<JsonNode, JsonNode> stale) {
		List<Path> written = new ArrayList<>();
		for (KeptRun run : runs) {
			Optional<ObjectNode> today = this.baselines.of(run);
			if (today.isEmpty()) {
				this.log.line(run.name() + ": skipped, no calibration");
				continue;
			}
			this.log.line(run.name() + ": " + summary(today.get()));
			Path file = run.file(KeptRun.BASELINES);
			Optional<JsonNode> recorded = ResultFiles.read(file);
			if (recorded.isEmpty() || stale.test(recorded.get(), today.get())) {
				ResultFiles.write(file, today.get());
				written.add(file);
			}
		}
		return written;
	}

	// the three knees
	private static String summary(JsonNode file) {
		JsonNode baselines = file.get("baselines");
		return "knee by bottleneck arithmetic " + knee(baselines, Baselines.ARITHMETIC) + ", by the engine's "
				+ "estimate " + knee(baselines, Baselines.ESTIMATE) + ", by utilisation extrapolation "
				+ knee(baselines, Baselines.EXTRAPOLATION) + " req/s";
	}

	private static String knee(JsonNode baselines, String estimate) {
		return PythonText.str(baselines.get(estimate).get(Baselines.KNEE));
	}

}
