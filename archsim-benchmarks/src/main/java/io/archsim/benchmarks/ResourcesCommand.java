package io.archsim.benchmarks;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

import io.archsim.benchmarks.comparison.Recomparison;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.resimulation.LaterResources;

/**
 * {@code resources}: predicts the CPU and memory of each experiment's latest results
 * whose predictions have none, then recomputes every comparison, without Docker.
 */
@Command(name = "resources",
		description = "predict CPU and memory for results whose predictions have none, then compare (no Docker)",
		mixinStandardHelpOptions = true, version = Benchmarks.VERSION)
final class ResourcesCommand implements Callable<Integer>, HarnessRules.JavaEngineOnly {

	private final RepositoryLayout layout;

	private final HarnessLog log;

	ResourcesCommand(RepositoryLayout layout, HarnessLog log) {
		this.layout = layout;
		this.log = log;
	}

	@Override
	public Integer call() {
		new LaterResources(Simulations.onTheEngine(), this.log.clock(), this.log).predict(this.layout.results())
			.forEach((written) -> this.log.line("wrote " + this.layout.name(written)));
		Recomparison.recompare(this.layout.results())
			.forEach((written) -> this.log.line("wrote " + this.layout.name(written)));
		return 0;
	}

}
