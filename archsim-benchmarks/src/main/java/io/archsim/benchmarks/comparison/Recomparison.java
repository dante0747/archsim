package io.archsim.benchmarks.comparison;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.KeptRuns;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonValues;

/**
 * Every kept run's comparison recomputed from its predictions and measurements, without
 * Docker: {@code compare}.
 */
public final class Recomparison {

	private Recomparison() {
	}

	/**
	 * A kept run's comparison, from its {@code predictions.json}, {@code measured.json}
	 * and, when there, {@code resources-predicted.json}.
	 * @param run the run
	 * @return its comparison
	 * @throws IllegalStateException when the run has no predictions or measurements
	 */
	public static ObjectNode comparisonOf(KeptRun run) {
		return Comparison.compare(Experiments.standard().get(run.experiment()), required(run, KeptRun.PREDICTIONS),
				required(run, KeptRun.MEASURED),
				ResultFiles.read(run.file(KeptRun.LATER_RESOURCES)).orElse(PythonValues.none()));
	}

	/**
	 * Writes every kept run's {@code comparison.json} again, over the one there.
	 * @param results the results folder
	 * @return the files written, in the kept runs' order
	 */
	public static List<Path> recompare(Path results) {
		List<Path> written = new ArrayList<>();
		for (KeptRun run : KeptRuns.of(results)) {
			if (Files.exists(run.file(KeptRun.PREDICTIONS))) {
				ResultFiles.write(run.file(KeptRun.COMPARISON), comparisonOf(run));
				written.add(run.file(KeptRun.COMPARISON));
			}
		}
		return written;
	}

	private static JsonNode required(KeptRun run, String name) {
		return ResultFiles.read(run.file(name))
			.orElseThrow(() -> new IllegalStateException(run.directory() + " has no " + name + ".json"));
	}

}
