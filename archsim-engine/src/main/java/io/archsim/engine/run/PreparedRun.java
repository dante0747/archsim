package io.archsim.engine.run;

import org.jspecify.annotations.Nullable;

import io.archsim.model.document.ArchitectureDocument;

/**
 * A run prepared to be queued.
 *
 * @param document the design to store with the run, upgraded to the current schema
 * @param resolved the document the run simulates: the scenario applied, the seed replaced
 * @param scenarioId the scenario it simulates; {@code null} for the design itself
 * @param scenarioName that scenario's name
 */
public record PreparedRun(ArchitectureDocument document, ArchitectureDocument resolved, @Nullable String scenarioId,
		@Nullable String scenarioName) {

	/**
	 * The seed the run simulates with.
	 * @return the resolved workload's seed
	 */
	public long seed() {
		return this.resolved.workload().seed();
	}

	/**
	 * Whether a quick check can answer the run.
	 * @return the answer, and why
	 */
	public QuickCheck quickCheck() {
		return QuickCheck.of(this.resolved.workload());
	}

}
