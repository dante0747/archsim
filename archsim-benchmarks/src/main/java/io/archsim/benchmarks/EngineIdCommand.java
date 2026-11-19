package io.archsim.benchmarks;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.history.GitRepository;
import io.archsim.benchmarks.provenance.EngineBuild;
import io.archsim.benchmarks.provenance.EngineFreeze;
import io.archsim.benchmarks.provenance.MachineName;
import io.archsim.benchmarks.provenance.Provenance;
import io.archsim.benchmarks.results.Timestamps;
import io.archsim.engine.version.EngineVersions;

/**
 * {@code engine-id}: prints what identifies the Java engine as it is, and what freezing
 * it would record in {@code engines.json}, without Docker. Nothing is written: a freeze
 * is a tag and an entry a person makes (ADR-0088).
 */
@Command(name = "engine-id",
		description = "print what identifies the Java engine as it is, and what freezing it records (no Docker)",
		mixinStandardHelpOptions = true, version = Benchmarks.VERSION)
final class EngineIdCommand implements Callable<Integer>, HarnessRules.JavaEngineOnly {

	private final RepositoryLayout layout;

	private final HarnessLog log;

	EngineIdCommand(RepositoryLayout layout, HarnessLog log) {
		this.layout = layout;
		this.log = log;
	}

	@Override
	public Integer call() {
		Provenance provenance = new Provenance(new GitRepository(this.layout.root()), MachineName.current(),
				EngineBuild.current().orElse(null));
		new EngineFreeze(Engines.read(this.layout.engines()))
			.lines(EngineVersions.ENGINE, provenance.of(), Timestamps.now(this.log.clock()))
			.forEach(this.log::line);
		return 0;
	}

}
