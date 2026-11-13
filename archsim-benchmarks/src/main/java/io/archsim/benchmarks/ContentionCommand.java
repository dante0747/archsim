package io.archsim.benchmarks;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import io.archsim.benchmarks.machine.ContentionCheck;

/**
 * {@code contention [--probe-only | --service-only]}: how much slower CPU work runs on
 * this machine with its hyperthread sibling, other cores or itself busy, and writes
 * {@code results/envcheck/<at>-contention.json}.
 */
@Command(name = "contention",
		description = "how much slower CPU work runs with its hyperthread sibling, other cores or itself busy (a "
				+ "probe, then pg-mixed's service; writes results/envcheck/; about 15 minutes of Docker)",
		mixinStandardHelpOptions = true, version = Benchmarks.VERSION)
final class ContentionCommand implements Callable<Integer>, HarnessRules.JavaEngineOnly {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Option(names = "--probe-only", description = "the probe part only")
	private boolean probeOnly;

	@Option(names = "--service-only", description = "the machine at rest and the service at three rates only")
	private boolean serviceOnly;

	private final HarnessSetup setup;

	ContentionCommand(HarnessSetup setup) {
		this.setup = setup;
	}

	/**
	 * Runs the check's parts and writes it.
	 * @return 0 once it is written
	 * @throws ParameterException when both parts are left out, before Docker starts
	 */
	@Override
	public Integer call() {
		if (this.probeOnly && this.serviceOnly) {
			throw new ParameterException(this.spec.commandLine(),
					"contention: --probe-only and --service-only together leave nothing to check");
		}
		Path written = new ContentionCheck(this.setup.checkTools()).run(this.setup.layout().results(), parts());
		this.setup.log().line("wrote " + this.setup.layout().name(written));
		return 0;
	}

	private ContentionCheck.Parts parts() {
		if (this.probeOnly) {
			return ContentionCheck.Parts.PROBE_ONLY;
		}
		if (this.serviceOnly) {
			return ContentionCheck.Parts.SERVICE_ONLY;
		}
		return ContentionCheck.Parts.BOTH;
	}

}
