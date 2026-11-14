package io.archsim.engine.reporting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.confidence.RunConfidence;
import io.archsim.engine.cost.CostEstimator;
import io.archsim.engine.estimate.InstantEstimate;
import io.archsim.engine.estimate.InstantEstimator;
import io.archsim.engine.findings.RunAnalysis;
import io.archsim.engine.provenance.Input;
import io.archsim.engine.report.RunReport;
import io.archsim.engine.review.DesignReview;
import io.archsim.engine.run.PreparedRun;
import io.archsim.engine.sizing.CoresBusy;
import io.archsim.engine.sizing.SizingSummaries;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.Architecture;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.ProvenanceKind;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * The analyses a report adds to a run's figures, each by the engine's own port of it: the
 * findings, and then the sizing, the design simulated, its cost after the run, its
 * review, its instant estimate and the confidence the run deserves. Like the Python
 * engine's, the cost and the confidence read the report as written so far.
 */
final class RunAnalyses {

	private final RunAnalysis analysis;

	private final InstantEstimator estimator;

	private final SizingSummaries sizing;

	private final CostEstimator costs;

	private final DesignReview review;

	private final RunConfidence confidence;

	RunAnalyses(ComponentRegistry registry, PriceSnapshots prices) {
		this.analysis = new RunAnalysis(registry);
		this.estimator = new InstantEstimator(registry);
		this.sizing = new SizingSummaries(registry, prices);
		this.costs = new CostEstimator(registry, prices);
		this.review = new DesignReview(registry);
		this.confidence = new RunConfidence(registry);
	}

	/**
	 * What a run found.
	 * @param report the run's report, read once its parts have their status
	 * @param resolved the design as the run simulated it
	 * @return the findings, the bottlenecks first
	 */
	ArrayNode findings(RunReport report, ArchitectureDocument resolved) {
		ArrayNode findings = Figures.NODES.arrayNode();
		this.analysis.analyze(report, resolved).forEach((finding) -> findings.add(finding.toJson()));
		return findings;
	}

	/**
	 * Writes the sizing, the design simulated, its cost, review and instant estimate, in
	 * that order.
	 * @param report the report so far
	 * @param prepared the run as it was prepared
	 * @param input the design simulated, as the report gives it
	 * @param simulated the same design read: priced and reviewed as simulated (auto sizes
	 * filled in, scenario applied)
	 */
	void writeDesign(DraftReport report, PreparedRun prepared, ObjectNode input, ArchitectureDocument simulated) {
		ArchitectureDocument resolved = prepared.resolved();
		InstantEstimate estimate = this.estimator.estimate(resolved);
		report.set("sizing",
				RecordCodec.write(this.sizing.of(prepared.document(), resolved, prepared.sizes(), estimate)));
		report.set("input", input);
		report.set("cost", RecordCodec.write(this.costs.estimateAfterRun(simulated, provenanceKinds(resolved),
				CoresBusy.of(prepared.sizes()), report.read())));
		ArrayNode findings = Figures.NODES.arrayNode();
		this.review.review(simulated).forEach((finding) -> findings.add(RecordCodec.write(finding)));
		report.set("review", findings);
		report.set("estimate", estimate.toJson());
	}

	/**
	 * Writes the confidence the run deserves, read from the report as written so far.
	 * @param report the report so far, with its findings and cost
	 * @param simulated the architecture simulated
	 * @param inputs each input of the design, with where its value came from
	 */
	void writeConfidence(DraftReport report, Architecture simulated, List<Input> inputs) {
		report.set("confidence",
				RecordCodec.write(this.confidence.of(report.read(), simulated, inputs, linkLatencies(simulated))));
	}

	private static Map<String, ProvenanceKind> provenanceKinds(ArchitectureDocument resolved) {
		Map<String, ProvenanceKind> kinds = new LinkedHashMap<>();
		resolved.provenance().forEach((path, record) -> kinds.put(path, record.kind()));
		return kinds;
	}

	private static Map<String, Double> linkLatencies(Architecture simulated) {
		Map<String, Double> latencies = new LinkedHashMap<>();
		for (EdgeSpec edge : simulated.edges()) {
			latencies.put(edge.id(), edge.config().latencyMs().expected());
		}
		return latencies;
	}

}
