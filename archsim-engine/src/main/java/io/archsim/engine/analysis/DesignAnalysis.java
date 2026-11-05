package io.archsim.engine.analysis;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.confidence.Confidence;
import io.archsim.engine.cost.CostEstimate;
import io.archsim.engine.estimate.InstantEstimate;
import io.archsim.engine.provenance.Input;
import io.archsim.engine.provenance.InputProvenances;
import io.archsim.engine.review.Finding;
import io.archsim.engine.review.ReviewRule;
import io.archsim.engine.review.SeverityCounts;
import io.archsim.engine.run.QuickCheck;
import io.archsim.engine.sizing.SizingSummary;
import io.archsim.engine.technology.PartTechnology;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.CalibrationRecord;

/**
 * A design's static analysis, as {@code POST /analyze} answers it.
 *
 * @param scenarioId the scenario analyzed; {@code null} for the design itself
 * @param peakRps the workload's peak load, in requests per second
 * @param sizing each part's specs and where they came from, its needs and its size's
 * price
 * @param cost the monthly cost before a run
 * @param review the design review's findings, most severe first
 * @param technologies each part's technology and its tier
 * @param inputs each input, with where its value came from
 * @param estimate what queueing formulas predict at the peak
 * @param confidence how far the estimate can be trusted
 * @param quickCheck whether a quick check can answer a run of it
 * @param calibration the design's last calibration from telemetry; {@code null} for none
 */
public record DesignAnalysis(@Nullable String scenarioId, double peakRps, SizingSummary sizing, CostEstimate cost,
		List<Finding> review, List<PartTechnology> technologies, List<Input> inputs, InstantEstimate estimate,
		Confidence confidence, QuickCheck quickCheck, @Nullable CalibrationRecord calibration) {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/**
	 * Copies the lists, so the record does not change.
	 */
	public DesignAnalysis {
		review = List.copyOf(review);
		technologies = List.copyOf(technologies);
		inputs = List.copyOf(inputs);
	}

	/**
	 * The analysis as the API answers it, with the review's counts by severity, the
	 * rules' titles and the inputs' counts by kind beside the pieces. The estimate's
	 * parts come in two shapes, which {@code RecordCodec} cannot write as one list, so
	 * the analysis is put together here.
	 * @return the JSON object
	 */
	public ObjectNode toJson() {
		ObjectNode json = NODES.objectNode().put("scenarioId", this.scenarioId).put("peakRps", this.peakRps);
		json.set("sizing", RecordCodec.write(this.sizing));
		json.set("cost", RecordCodec.write(this.cost));
		json.set("review", records(this.review));
		json.set("reviewCounts", RecordCodec.write(SeverityCounts.of(this.review)));
		ObjectNode rules = json.putObject("reviewRules");
		ReviewRule.titles().forEach(rules::put);
		json.set("technologies", records(this.technologies));
		json.set("inputs", records(this.inputs));
		ObjectNode counts = json.putObject("provenanceCounts");
		InputProvenances.counts(this.inputs).forEach((kind, count) -> counts.put(kind.json(), count));
		json.set("estimate", this.estimate.toJson());
		json.set("confidence", RecordCodec.write(this.confidence));
		json.set("quickCheck", RecordCodec.write(this.quickCheck));
		json.set("calibration", (this.calibration != null) ? RecordCodec.write(this.calibration) : NODES.nullNode());
		return json;
	}

	private static ArrayNode records(List<? extends Record> records) {
		ArrayNode array = NODES.arrayNode();
		records.forEach((record) -> array.add(RecordCodec.write(record)));
		return array;
	}

}
