package io.archsim.benchmarks;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.history.GitRepository;
import io.archsim.benchmarks.inputs.ModelInputs;
import io.archsim.benchmarks.machine.MachineFit;
import io.archsim.benchmarks.provenance.EngineBuild;
import io.archsim.benchmarks.provenance.MachineName;
import io.archsim.benchmarks.provenance.Provenance;
import io.archsim.benchmarks.range.KneeRanges;
import io.archsim.benchmarks.range.RangeFit;
import io.archsim.benchmarks.resimulation.CheckStamp;
import io.archsim.engine.version.EngineVersions;
import io.archsim.model.text.PythonFormat;

/**
 * {@code range-fit}: fits the predicted range of the knee from the in-sample runs and
 * checks its coverage, and writes {@code benchmarks/knee-range.json}, without Docker.
 */
@Command(name = "range-fit",
		description = "fit the predicted range of the knee from the in-sample runs and check its coverage (writes "
				+ "benchmarks/knee-range.json; run resimulate first to reuse its inputs and knees)",
		mixinStandardHelpOptions = true, version = Benchmarks.VERSION)
final class RangeFitCommand implements Callable<Integer>, HarnessRules.JavaEngineOnly {

	@Option(names = "--reuse-dev", paramLabel = "LABEL",
			description = "take the kept runs' inputs and knees from this engine label's development checks (only "
					+ "when nothing but the label changed since)")
	private @Nullable String reuse;

	private final HarnessSetup setup;

	private final RepositoryLayout layout;

	private final HarnessLog log;

	private final Path cache;

	RangeFitCommand(HarnessSetup setup) {
		this.setup = setup;
		this.layout = setup.layout();
		this.log = setup.log();
		this.cache = setup.rangeFitCache();
	}

	@Override
	public Integer call() {
		Provenance provenance = new Provenance(new GitRepository(this.layout.root()), MachineName.current(),
				EngineBuild.current().orElse(null));
		CheckStamp stamp = new CheckStamp(EngineVersions.ENGINE, provenance.of(), this.log.clock());
		Optional<MachineFit> machine = this.setup.machine();
		ModelInputs inputs = new ModelInputs(Simulations.onTheEngine(), machine.orElse(null));
		Path cacheFile = machine.map((host) -> RangeFit.cachePath(this.cache, host)).orElse(this.cache);
		ObjectNode fit = new RangeFit(this.layout.results(), inputs, stamp, this.log).fit(this.layout.kneeRange(),
				this.reuse, cacheFile);
		String level = PythonFormat.format(KneeRanges.COVERAGE, "g");
		fit.get("check").get("counts").properties().forEach((fold) -> {
			JsonNode counts = fold.getValue().get(level);
			this.log.line("range fit: " + fold.getKey() + ": " + counts.get(0) + " of " + counts.get(1)
					+ " runs inside the " + PythonFormat.format(KneeRanges.COVERAGE, ".0%") + " range");
		});
		this.log.line("wrote " + this.layout.name(this.layout.kneeRange()));
		return 0;
	}

}
