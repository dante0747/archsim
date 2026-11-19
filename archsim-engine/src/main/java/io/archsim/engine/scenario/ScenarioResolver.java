package io.archsim.engine.scenario;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.host.SiblingSpeeds;
import io.archsim.engine.sizing.AutoSizer;
import io.archsim.engine.sizing.SizedDocument;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Scenario;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.model.scenario.Scenarios;

/**
 * Resolves the document a run simulates: the design with its defaults in full, its price
 * snapshot pinned, its empty specs auto-sized, a scenario's overrides applied, and the
 * sibling speed each part's vCPUs count at written into the part that sets none
 * ({@link SiblingSpeeds#writeInto}). Sizes come from the design as drawn: a scenario runs
 * on the same sizes (its overrides can still set a spec). A price snapshot is resolved
 * once and written into {@code pricing} (date and content hash), so sizing, cost and the
 * run's input all use, and name, the same one.
 */
public final class ScenarioResolver {

	private final ComponentRegistry registry;

	private final PriceSnapshots prices;

	private final AutoSizer sizer;

	private final SiblingSpeeds siblingSpeeds;

	/**
	 * A resolver for designs of the given component types and price snapshots.
	 * @param registry the component types
	 * @param prices the price snapshots
	 */
	public ScenarioResolver(ComponentRegistry registry, PriceSnapshots prices) {
		this.registry = registry;
		this.prices = prices;
		this.sizer = new AutoSizer(registry, prices);
		this.siblingSpeeds = new SiblingSpeeds(prices);
	}

	/**
	 * The document to simulate for a design or one of its scenarios.
	 * @param design the design, as loaded
	 * @param scenarioId the scenario, or {@code null} for the design itself
	 * @return the resolved document, the scenario and the auto sizes
	 * @throws io.archsim.model.issue.DocumentInvalidException when the design has no such
	 * scenario, an override cannot be applied, or the result is not a valid document
	 */
	public ResolvedDesign resolve(ArchitectureDocument design, @Nullable String scenarioId) {
		ObjectNode base = DesignDocuments.normalize(design, this.registry);
		JsonNode pinned = this.prices.pinned(base.get("pricing"));
		if (pinned != null) {
			base.set("pricing", pinned);
		}
		SizedDocument sized = this.sizer.applyTo(base);
		ObjectNode resolved = sized.document();
		Scenario scenario = null;
		if (scenarioId != null && !scenarioId.isEmpty()) {
			scenario = Scenarios.find(design, scenarioId);
			resolved = Scenarios.applyOverrides(resolved, scenario.overrides(), "Scenario '" + scenario.name() + "'");
			Scenarios.dropStaleInstanceTypes(resolved, design, scenario);
		}
		resolved.putArray("scenarios");
		resolved.putObject("layout").putObject("nodes");
		// each part's vCPUs as they count, written into it as auto-sizing records the
		// instance type it chose: the simulation and every estimate read it there
		return new ResolvedDesign(this.siblingSpeeds.writeInto(resolved), scenario, sized.sizes());
	}

}
