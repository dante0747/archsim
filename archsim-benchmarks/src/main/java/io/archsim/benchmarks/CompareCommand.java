package io.archsim.benchmarks;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

import io.archsim.benchmarks.comparison.Recomparison;

/**
 * {@code compare}: recomputes every kept run's {@code comparison.json} from its
 * {@code measured.json} and {@code predictions.json}, without Docker.
 */
@Command(name = "compare",
		description = "recompute every comparison.json from its measured.json and predictions.json (no Docker)",
		mixinStandardHelpOptions = true, version = Benchmarks.VERSION)
final class CompareCommand implements Callable<Integer>, HarnessRules.JavaEngineOnly {

	private final RepositoryLayout layout;

	private final HarnessLog log;

	CompareCommand(RepositoryLayout layout, HarnessLog log) {
		this.layout = layout;
		this.log = log;
	}

	@Override
	public Integer call() {
		Recomparison.recompare(this.layout.results())
			.forEach((written) -> this.log.line("wrote " + this.layout.name(written)));
		return 0;
	}

}
