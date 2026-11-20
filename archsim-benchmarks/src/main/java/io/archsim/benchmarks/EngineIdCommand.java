package io.archsim.benchmarks;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.history.GitRepository;
import io.archsim.benchmarks.machine.ContentionChecks;
import io.archsim.benchmarks.machine.HostSwitch;
import io.archsim.benchmarks.machine.MachineFit;
import io.archsim.benchmarks.provenance.EngineBuild;
import io.archsim.benchmarks.provenance.EngineFreeze;
import io.archsim.benchmarks.provenance.FreezeHost;
import io.archsim.benchmarks.provenance.MachineName;
import io.archsim.benchmarks.provenance.Provenance;
import io.archsim.benchmarks.results.Timestamps;
import io.archsim.engine.version.EngineVersions;

/**
 * {@code engine-id}: prints what identifies the Java engine as it is, and what freezing
 * it would record in {@code engines.json}, without Docker: the designs' host machine
 * among it, when the committed contention checks give it (ADR-0095). Nothing is written:
 * a freeze is a tag and an entry a person makes (ADR-0088).
 */
@Command(name = "engine-id",
		description = "print what identifies the Java engine as it is, and what freezing it records (no Docker)",
		mixinStandardHelpOptions = true, version = Benchmarks.VERSION)
final class EngineIdCommand implements Callable<Integer>, HarnessRules.JavaEngineOnly {

	private final HarnessSetup setup;

	EngineIdCommand(HarnessSetup setup) {
		this.setup = setup;
	}

	@Override
	public Integer call() {
		RepositoryLayout layout = this.setup.layout();
		GitRepository git = new GitRepository(layout.root());
		Provenance provenance = new Provenance(git, MachineName.of(this.setup.environment()),
				EngineBuild.current().orElse(null));
		new EngineFreeze(Engines.read(layout.engines()))
			.lines(EngineVersions.ENGINE, provenance.of(), Timestamps.now(this.setup.log().clock()), host(git))
			.forEach(this.setup.log()::line);
		return 0;
	}

	// the host machine a freeze would name: none, the fit the committed contention checks
	// give, or why the repository could not give it again
	private FreezeHost host(GitRepository git) {
		Map<String, String> environment = this.setup.environment();
		if (!HostSwitch.isOn(environment)) {
			return new FreezeHost.None();
		}
		Optional<String> whatIf = HostSwitch.whatIf(environment);
		if (whatIf.isPresent()) {
			return new FreezeHost.Unrepeatable(whatIf.get() + " puts the designs on a what-if host machine, which the "
					+ "repository cannot give again: unset it to freeze the engine");
		}
		RepositoryLayout layout = this.setup.layout();
		String checks = layout.root()
			.relativize(ContentionChecks.folder(layout.results()))
			.toString()
			.replace('\\', '/');
		if (git.hasChanges(List.of(checks)).orElse(true)) {
			return new FreezeHost.Unrepeatable("the checks in " + checks + " have uncommitted changes (or git "
					+ "cannot tell): commit them first, so that the host machine named is the repository's");
		}
		return this.setup.machine()
			.map(MachineFit::digest)
			.<FreezeHost>map(FreezeHost.Fit::new)
			.orElseGet(FreezeHost.None::new);
	}

}
