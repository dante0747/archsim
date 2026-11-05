package io.archsim.engine;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.explain.QueueingFormulas;
import io.archsim.engine.findings.RunAnalysis;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.RunReport;
import io.archsim.engine.simulation.Stationarity;
import io.archsim.engine.simulation.SteadyState;
import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.parity.GoldenEntry;

/**
 * Reading a run's report: whether it reached a steady state, each part's formulas, and
 * what the run found.
 */
final class ReportFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.engine.stationary", ReportFunctions::stationary),
			Map.entry("archsim_engine.explain.generic", ReportFunctions::generic),
			Map.entry("archsim_engine.analysis.analyze", ReportFunctions::analyze));

	private static final RunAnalysis ANALYSIS = new RunAnalysis(StandardComponents.registry());

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

}
