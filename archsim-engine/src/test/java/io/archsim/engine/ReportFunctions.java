package io.archsim.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.confidence.RunConfidence;
import io.archsim.engine.explain.Formula;
import io.archsim.engine.explain.QueueingFormulas;
import io.archsim.engine.findings.RunAnalysis;
import io.archsim.engine.provenance.Input;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.report.ModelNotes;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.RunReport;
import io.archsim.engine.simulation.Stationarity;
import io.archsim.engine.simulation.SteadyState;
import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.parity.GoldenEntry;

/**
 * Reading a run's report: whether it reached a steady state, each part's formulas, what
 * the run found and how far it can be trusted; and the words a report is written with.
 */
final class ReportFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.engine.stationary", ReportFunctions::stationary),
			Map.entry("archsim_engine.explain.generic", ReportFunctions::generic),
			Map.entry("archsim_engine.analysis.analyze", ReportFunctions::analyze),
			Map.entry("archsim_engine.confidence.report_confidence", ReportFunctions::reportConfidence),
			Map.entry("archsim_engine.report.MODEL_NOTES", ReportFunctions::modelNotes),
			Map.entry("archsim_engine.report.REASON_TEXT", ReportFunctions::reasonText));

	private static final RunAnalysis ANALYSIS = new RunAnalysis(StandardComponents.registry());

	private static final RunConfidence CONFIDENCE = new RunConfidence(StandardComponents.registry());

	private ReportFunctions() {
	}

	/** {@code (ok, checks)}. */
	private static JsonNode stationary(GoldenEntry entry) {
		SteadyState state = Stationarity.judge(RunReport.read(entry.input("report")));
		JsonNode checks = switch (state) {
			case SteadyState.Judged judged -> RecordCodec.write(judged);
			case SteadyState.Unjudged unjudged -> RecordCodec.write(unjudged);
		};
		return GoldenJson.NODES.arrayNode().add(state.reached()).add(checks);
	}

	/**
	 * {@code generic(node, window_s, pool_based)}: the slots' formulas only for a part
	 * that has them.
	 */
	private static JsonNode generic(GoldenEntry entry) {
		ArrayNode formulas = GoldenJson.NODES.arrayNode();
		ReportNode node = ReportNode.read(entry.input("node"));
		double windowS = entry.input("windowS").doubleValue();
		List<Formula> generic = entry.input("poolBased").booleanValue() ? QueueingFormulas.ofSlots(node, windowS)
				: QueueingFormulas.of(node, windowS);
		generic.forEach((formula) -> formulas.add(RecordCodec.write(formula)));
		return formulas;
	}

	/** {@code analyze(report, resolved)}: the run's findings. */
	private static JsonNode analyze(GoldenEntry entry) {
		ArrayNode findings = GoldenJson.NODES.arrayNode();
		ANALYSIS.analyze(RunReport.read(entry.input("report")), DesignDocuments.parse(entry.input("resolved")))
			.forEach((finding) -> findings.add(finding.toJson()));
		return findings;
	}

	/**
	 * {@code report_confidence(report, inputs, link_ms, registry)}: the design the run
	 * simulated is the report's {@code input}.
	 */
	private static JsonNode reportConfidence(GoldenEntry entry) {
		JsonNode report = entry.input("report");
		List<Input> inputs = new ArrayList<>();
		entry.input("inputs").forEach((input) -> inputs.add(RecordCodec.read(input, Input.class)));
		Map<String, Double> linkMs = new LinkedHashMap<>();
		entry.input("linkMs").properties().forEach((link) -> linkMs.put(link.getKey(), link.getValue().doubleValue()));
		return RecordCodec.write(CONFIDENCE.of(RunReport.read(report),
				DesignDocuments.parse(report.path("input")).architecture(), inputs, linkMs));
	}

	private static JsonNode modelNotes(GoldenEntry entry) {
		ArrayNode notes = GoldenJson.NODES.arrayNode();
		ModelNotes.all().forEach(notes::add);
		return notes;
	}

	private static JsonNode reasonText(GoldenEntry entry) {
		ObjectNode texts = GoldenJson.NODES.objectNode();
		for (FailureReason reason : FailureReason.values()) {
			texts.put(reason.json(), reason.text());
		}
		return texts;
	}

}
