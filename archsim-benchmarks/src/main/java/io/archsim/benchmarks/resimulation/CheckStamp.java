package io.archsim.benchmarks.resimulation;

import java.nio.file.Path;
import java.time.Clock;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.history.GitRepository;
import io.archsim.benchmarks.provenance.EngineBuild;
import io.archsim.benchmarks.provenance.MachineName;
import io.archsim.benchmarks.provenance.Provenance;

/**
 * What names a development check, a range fit or a run's baselines: the engine's label it
 * is written under, what produced it and the clock that dates it.
 *
 * @param label the label ({@link DevLabels}), which names the folder under
 * {@code results/dev/}
 * @param provenance the commit, trees, build and machine
 * ({@link io.archsim.benchmarks.provenance.Provenance})
 * @param clock the clock
 */
public record CheckStamp(String label, JsonNode provenance, Clock clock) {

	/**
	 * Copies the provenance.
	 */
	public CheckStamp {
		provenance = provenance.deepCopy();
	}

	/**
	 * The stamp of what runs now in a repository: its commit and trees, the engine's
	 * build in this process and this machine.
	 * @param label the label
	 * @param repository the repository's root
	 * @param clock the clock
	 * @return the stamp
	 */
	public static CheckStamp of(String label, Path repository, Clock clock) {
		Provenance provenance = new Provenance(new GitRepository(repository), MachineName.current(),
				EngineBuild.current().orElse(null));
		return new CheckStamp(label, provenance.of(), clock);
	}

}
