package io.archsim.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.analysis.DesignAnalyzer;
import io.archsim.engine.confidence.AnalysisConfidence;
import io.archsim.engine.estimate.Busiest;
import io.archsim.engine.estimate.ClientPath;
import io.archsim.engine.estimate.EstimatedPart;
import io.archsim.engine.estimate.InstantEstimate;
import io.archsim.engine.estimate.Latency;
import io.archsim.engine.estimate.PartEstimate;
import io.archsim.engine.estimate.TimeShare;
import io.archsim.engine.estimate.UnestimatedPart;
import io.archsim.engine.provenance.Input;
import io.archsim.engine.run.QuickCheck;
import io.archsim.engine.technology.PartTechnology;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.Architecture;
import io.archsim.model.document.Workload;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.GoldenEntry;

/**
 * The static analysis of a design: its confidence, whether a quick check can answer a run
 * of it, and the whole analysis {@code POST /analyze} answers.
 */
final class AnalysisFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.confidence.analysis_confidence", AnalysisFunctions::analysisConfidence),
			Map.entry("archsim_engine.engine.quick_check", AnalysisFunctions::quickCheck),
			Map.entry("archsim_engine.analyze.analyze_document", AnalysisFunctions::analyzeDocument));

	private static final AnalysisConfidence CONFIDENCE = new AnalysisConfidence(StandardComponents.registry());

	private static final DesignAnalyzer ANALYZER = new DesignAnalyzer(StandardComponents.registry(),
			PriceSnapshots.shipped());

	private AnalysisFunctions() {
	}

	private static JsonNode analysisConfidence(GoldenEntry entry) {
		List<Input> inputs = new ArrayList<>();
		entry.input("inputs").forEach((input) -> inputs.add(RecordCodec.read(input, Input.class)));
		List<PartTechnology> technologies = new ArrayList<>();
		entry.input("technologies")
			.forEach((technology) -> technologies.add(RecordCodec.read(technology, PartTechnology.class)));
		return RecordCodec.write(CONFIDENCE.of(estimate(entry.input("estimate")), inputs, technologies,
				RecordCodec.read(entry.input("architecture"), Architecture.class)));
	}

	private static JsonNode quickCheck(GoldenEntry entry) {
		return RecordCodec.write(QuickCheck.of(RecordCodec.read(entry.input("workload"), Workload.class)));
	}

	private static JsonNode analyzeDocument(GoldenEntry entry) {
		JsonNode scenario = entry.input("scenarioId");
		return ANALYZER.analyze(entry.input("document"), scenario.isString() ? scenario.asString() : null).toJson();
	}

	/**
	 * An instant estimate as the golden files record it: the parts the formulas could not
	 * estimate are those with a note.
	 * @param json the estimate's JSON
	 * @return the estimate
	 */
	private static InstantEstimate estimate(JsonNode json) {
		List<PartEstimate> parts = new ArrayList<>();
		json.path("parts")
			.forEach((part) -> parts.add(part.has("note") ? RecordCodec.read(part, UnestimatedPart.class)
					: RecordCodec.read(part, EstimatedPart.class)));
		List<ClientPath> paths = new ArrayList<>();
		json.path("paths").forEach((path) -> paths.add(RecordCodec.read(path, ClientPath.class)));
		List<TimeShare> breakdown = new ArrayList<>();
		json.path("breakdown").forEach((share) -> breakdown.add(RecordCodec.read(share, TimeShare.class)));
		List<String> notes = new ArrayList<>();
		json.path("notes").forEach((note) -> notes.add(note.asString()));
		return new InstantEstimate(json.path("method").asString(), GoldenJson.readNullableNumber(json.path("atRps")),
				json.path("latencyMs").isNull() ? null : RecordCodec.read(json.path("latencyMs"), Latency.class),
				json.path("p95Approximate").asBoolean(),
				json.path("busiest").isNull() ? null : RecordCodec.read(json.path("busiest"), Busiest.class),
				json.path("overloaded").asBoolean(), GoldenJson.readNullableNumber(json.path("saturationRps")),
				json.path("summary").asString(), paths, breakdown, parts, notes);
	}

}
