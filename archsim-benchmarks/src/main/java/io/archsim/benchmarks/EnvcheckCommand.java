package io.archsim.benchmarks;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

import io.archsim.benchmarks.machine.EnvironmentCheck;

/**
 * {@code envcheck}: characterises this machine on pg-mixed's workload and writes
 * {@code results/envcheck/<at>.json}.
 */
@Command(name = "envcheck",
		description = "characterise this machine: CPU siblings, steal, CPU per request across rates (writes "
				+ "results/envcheck/; about 8 minutes of Docker)",
		mixinStandardHelpOptions = true, version = Benchmarks.VERSION)
final class EnvcheckCommand implements Callable<Integer>, HarnessRules.JavaEngineOnly {

	private final HarnessSetup setup;

	EnvcheckCommand(HarnessSetup setup) {
		this.setup = setup;
	}

	@Override
	public Integer call() {
		Path written = new EnvironmentCheck(this.setup.checkTools()).run(this.setup.layout().results());
		this.setup.log().line("wrote " + this.setup.layout().name(written));
		return 0;
	}

}
