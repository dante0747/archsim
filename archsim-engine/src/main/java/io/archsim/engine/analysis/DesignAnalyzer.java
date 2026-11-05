package io.archsim.engine.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.confidence.AnalysisConfidence;
import io.archsim.engine.cost.CostEstimator;
import io.archsim.engine.estimate.InstantEstimate;
import io.archsim.engine.estimate.InstantEstimator;
import io.archsim.engine.provenance.Input;
import io.archsim.engine.provenance.InputProvenances;
import io.archsim.engine.review.DesignReview;
import io.archsim.engine.review.Finding;
import io.archsim.engine.run.QuickCheck;
import io.archsim.engine.scenario.ResolvedDesign;
import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.engine.sizing.BaseBusy;
import io.archsim.engine.sizing.SizingSummaries;
import io.archsim.engine.technology.PartTechnology;
import io.archsim.engine.technology.TechnologyTiers;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.ProvenanceKind;
import io.archsim.model.document.Scenario;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * The static analysis of a design ({@code analyze_document}), without simulating it: the
 * auto sizes, the cost estimate, the design review, each part's technology tier, where
 * every input came from, the instant estimate and the confidence it deserves, and whether
 * a quick check can answer it, exactly as a run would use them.
 */
public final class DesignAnalyzer {

	private final ScenarioResolver resolver;

	private final DesignReview review;

	private final InputProvenances provenances;

	private final TechnologyTiers tiers;

	private final InstantEstimator estimator;

	private final SizingSummaries summaries;

	private final CostEstimator costs;

	private final AnalysisConfidence confidence;

	/**
	 * Analyzes designs made of a registry's types.
	 * @param registry the component types
	 * @param prices the price snapshots on this machine
	 */
	public DesignAnalyzer(ComponentRegistry registry, PriceSnapshots prices) {
		this.resolver = new ScenarioResolver(registry, prices);
		this.review = new DesignReview(registry);
		this.provenances = new InputProvenances(registry);
		this.tiers = new TechnologyTiers(registry);
		this.estimator = new InstantEstimator(registry);
		this.summaries = new SizingSummaries(registry, prices);
		this.costs = new CostEstimator(registry, prices);
		this.confidence = new AnalysisConfidence(registry);
	}

	/**
	 * The analysis of a design, or of one of its scenarios.
	 * @param raw the design, as the request carries it
	 * @param scenarioId the scenario to analyze; {@code null} for the design itself
	 * @return the analysis
	 * @throws io.archsim.model.issue.DocumentInvalidException when the design cannot be
	 * read or resolved
	 */
	public DesignAnalysis analyze(JsonNode raw, @Nullable String scenarioId) {
		ArchitectureDocument design = DesignDocuments.load(raw);
		ResolvedDesign resolution = this.resolver.resolve(design, scenarioId);
		ArchitectureDocument resolved = resolution.document();
		// in the Python engine's order, so that of two steps that raise, the same one
		// does
		List<Finding> findings = this.review.review(resolved);
		List<Input> inputs = this.provenances.of(resolved);
		List<PartTechnology> technologies = this.tiers.of(resolved.architecture().nodes());
		InstantEstimate estimate = this.estimator.estimate(resolved);
		return new DesignAnalysis(resolution.scenario().map(Scenario::id).orElse(null), resolved.workload().peakRate(),
				this.summaries.of(design, resolved, resolution.sizes(), estimate),
				this.costs.estimate(resolved, provenanceKinds(resolved), BaseBusy.of(resolution.sizes()), estimate),
				findings, technologies, inputs, estimate,
				this.confidence.of(estimate, inputs, technologies, resolved.architecture()),
				QuickCheck.of(resolved.workload()), design.calibration());
	}

	private static Map<String, ProvenanceKind> provenanceKinds(ArchitectureDocument resolved) {
		Map<String, ProvenanceKind> kinds = new LinkedHashMap<>();
		resolved.provenance().forEach((path, record) -> kinds.put(path, record.kind()));
		return kinds;
	}

}
