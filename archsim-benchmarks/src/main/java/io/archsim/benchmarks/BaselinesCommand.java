package io.archsim.benchmarks;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import io.archsim.benchmarks.baselines.BaselineFiles;
import io.archsim.benchmarks.baselines.Baselines;
import io.archsim.benchmarks.designs.Estimates;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.resimulation.CheckStamp;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.KeptRuns;
import io.archsim.engine.version.EngineVersions;

/**
 * {@code baselines}: estimates every kept run's knee from its calibration alone, three
 * simple ways, and writes them beside the run's comparison as {@code baselines.json}
 * (ADR-0087), without Docker. No recorded file changes.
 */
@Command(name = "baselines",
		description = "estimate every kept run's knee from its calibration alone, three simple ways, to set beside "
				+ "the simulator's prediction (baselines.json; no Docker)",
		mixinStandardHelpOptions = true, version = Benchmarks.VERSION)
final class BaselinesCommand implements Callable<Integer> {

	@Option(names = "--rewrite",
			description = "write every file again, stamped anew; by default a file whose figures are today's "
					+ "stays as it is")
	private boolean rewrite;

	private final RepositoryLayout layout;

	private final HarnessLog log;

	BaselinesCommand(RepositoryLayout layout, HarnessLog log) {
		this.layout = layout;
		this.log = log;
	}

	@Override
	public Integer call() {
		CheckStamp stamp = CheckStamp.of(EngineVersions.ENGINE, this.layout.root(), this.log.clock());
		BaselineFiles files = new BaselineFiles(
				new Baselines(Estimates.onTheEngine(), Cpusets.declaredIn(this.layout.compose()), stamp), this.log);
		List<KeptRun> runs = KeptRuns.of(this.layout.results());
		List<Path> written = this.rewrite ? files.rewrite(runs) : files.write(runs);
		written.forEach((file) -> this.log.line("wrote " + this.layout.name(file)));
		this.log.line("baselines: " + written.size() + " of " + runs.size() + " kept runs' files written");
		return 0;
	}

}
