package io.archsim.engine.run;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.scenario.ResolvedDesign;
import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Scenario;
import io.archsim.model.document.Workload;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.validation.DocumentValidator;
import io.archsim.model.validation.ValidationResult;

/**
 * Prepares a run before it is queued ({@code prepare_run}): the design loaded, the
 * scenario resolved, the seed replaced, the result validated as a run simulates it, and
 * its requests counted against the run's limit.
 */
public final class RunPreparer {

	private final ScenarioResolver resolver;

	private final DocumentValidator validator;

	/**
	 * Prepares runs of designs made of a registry's types.
	 * @param registry the component types
	 * @param prices the price snapshots on this machine
	 */
	public RunPreparer(ComponentRegistry registry, PriceSnapshots prices) {
		this.resolver = new ScenarioResolver(registry, prices);
		this.validator = new DocumentValidator(registry, prices);
	}

	/**
	 * Prepares a run.
	 * @param raw the design, as the request carries it
	 * @param scenarioId the scenario to simulate; {@code null} for the design itself
	 * @param limits the run's limits
	 * @param seed the seed that replaces the workload's after the scenario is applied;
	 * {@code null} to keep it
	 * @return the run: the design to store with it, its scenario, and the document it
	 * simulates
	 * @throws DocumentInvalidException when the design cannot be read or simulated, or
	 * the seed is out of range
	 * @throws SimulationLimitException when the run would generate more requests than its
	 * limit
	 */
	public PreparedRun prepare(JsonNode raw, @Nullable String scenarioId, SimulationLimits limits,
			@Nullable Long seed) {
		ArchitectureDocument design = DesignDocuments.load(raw);
		ResolvedDesign resolution = this.resolver.resolve(design, scenarioId);
		ArchitectureDocument resolved = withSeed(resolution.document(), seed);
		ValidationResult result = this.validator.validate(resolved);
		if (!result.valid()) {
			throw new DocumentInvalidException(result.issues());
		}
		double expected = resolved.workload().expectedArrivals();
		if (!limits.allowsRequests(expected)) {
			throw new SimulationLimitException("This run would generate about " + PythonFormat.format(expected, ",.0f")
					+ " requests, above the limit of " + PythonFormat.format(limits.maxRequests(), ",")
					+ ". Reduce RPS or duration.");
		}
		return new PreparedRun(design, resolved, resolution.scenario().map(Scenario::id).orElse(null),
				resolution.scenario().map(Scenario::name).orElse(null),
				resolution.scenario().map(Scenario::overrides).orElse(List.of()), result, resolution.sizes());
	}

	/**
	 * A resolved design with its workload's seed replaced ({@code with_seed}).
	 * @param resolved the design
	 * @param seed the seed; {@code null} to keep the design's
	 * @return the design with that seed
	 * @throws DocumentInvalidException when the seed is not from 0 to 2<sup>32</sup> - 1
	 */
	private static ArchitectureDocument withSeed(ArchitectureDocument resolved, @Nullable Long seed) {
		if (seed == null || seed == resolved.workload().seed()) {
			return resolved;
		}
		if (seed < 0 || seed > Workload.MAX_SEED) {
			throw new DocumentInvalidException(List.of(Issue.error("workload.seed", "Seeds go from 0 to "
					+ PythonFormat.format(Workload.MAX_SEED, ",") + "; got " + PythonFormat.format(seed, ",") + ".")));
		}
		ObjectNode json = DesignDocuments.toJson(resolved);
		((ObjectNode) json.path("workload")).put("seed", seed);
		return DesignDocuments.parse(json);
	}

}
