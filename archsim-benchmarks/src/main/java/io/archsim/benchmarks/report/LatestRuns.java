package io.archsim.benchmarks.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.KeptRuns;
import io.archsim.benchmarks.results.ResultFiles;

/**
 * Each experiment's latest run, in the page's order: the held-out experiments first,
 * which test ArchSim on stacks it was not tuned on, then the four it was built with; and
 * the held-out experiments that have no run yet.
 */
final class LatestRuns {

	private LatestRuns() {
	}

	/**
	 * The experiments in the page's order.
	 * @return the held-out experiments, then the tuned-on ones
	 */
	static List<Experiment> inPageOrder() {
		List<Experiment> order = new ArrayList<>();
		Experiments.standard().all().stream().filter(Experiment::isHeldOut).forEach(order::add);
		Experiments.standard().all().stream().filter((experiment) -> !experiment.isHeldOut()).forEach(order::add);
		return order;
	}

	/**
	 * The held-out experiments that are registered and not yet measured: no run of theirs
	 * is kept. They are sealed until a frozen engine has predicted them, and the page
	 * gives each a section of its own once it is measured.
	 * @param results the results folder
	 * @return the experiments, in the page's order
	 */
	static List<Experiment> awaitingMeasurement(Path results) {
		Set<String> measured = KeptRuns.of(results).stream().map(KeptRun::experiment).collect(Collectors.toSet());
		return inPageOrder().stream()
			.filter((experiment) -> experiment.isHeldOut() && !measured.contains(experiment.id()))
			.toList();
	}

	/**
	 * A file of an experiment's latest run.
	 * @param results the results folder
	 * @param experiment the experiment's name
	 * @param name the file's name, {@code comparison} for {@code comparison.json}
	 * @return its value; empty when there is no such file
	 */
	static Optional<JsonNode> load(Path results, String experiment, String name) {
		return ResultFiles.read(results.resolve(experiment).resolve(name + ".json"));
	}

}
