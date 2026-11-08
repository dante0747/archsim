package io.archsim.engine.run;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.sizing.PartSize;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.ScenarioOverride;
import io.archsim.model.validation.ValidationResult;

/**
 * A run prepared to be queued.
 *
 * @param document the design to store with the run, upgraded to the current schema
 * @param resolved the document the run simulates: the scenario applied, the seed replaced
 * @param scenarioId the scenario it simulates; {@code null} for the design itself
 * @param scenarioName that scenario's name
 * @param overrides the values the scenario sets, in order; empty for the design itself
 * @param validation what validating the resolved document found: each part's config and
 * the request types each link carries
 * @param sizes each auto-sized part's size
 */
public record PreparedRun(ArchitectureDocument document, ArchitectureDocument resolved, @Nullable String scenarioId,
		@Nullable String scenarioName, List<ScenarioOverride> overrides, ValidationResult validation,
		Map<String, PartSize> sizes) {

	/**
	 * Copies the lists and maps, so the run does not change.
	 * @param document the design
	 * @param resolved the document the run simulates
	 * @param scenarioId the scenario
	 * @param scenarioName its name
	 * @param overrides its overrides
	 * @param validation the validation's findings
	 * @param sizes the auto-sized parts' sizes
	 */
	public PreparedRun {
		overrides = List.copyOf(overrides);
		sizes = Collections.unmodifiableMap(new LinkedHashMap<>(sizes));
	}

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
