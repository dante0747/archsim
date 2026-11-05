package io.archsim.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.report.RunReport;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.slo.StoredObjectives;
import io.archsim.engine.sweep.SweepAnalysis;
import io.archsim.engine.sweep.SweepRange;
import io.archsim.engine.sweep.SweepRow;
import io.archsim.model.codec.RecordCodec;
import io.archsim.parity.GoldenEntry;

/**
 * Load sweeps: their default range, the highest load a step can run, the levels, each
 * step's row and the breaking point.
 */
final class SweepFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.sweep.suggest_range", SweepFunctions::suggestRange),
			Map.entry("archsim_engine.sweep.rps_cap",
					(entry) -> GoldenJson
						.number(SweepRange.rpsCap(SimulationLimits.DEFAULT, entry.input("durationS").doubleValue()))),
			Map.entry("archsim_engine.sweep.levels", SweepFunctions::levels),
			Map.entry("archsim_engine.sweep.row_from",
					(entry) -> RecordCodec
						.write(SweepRow.of(entry.input("rps").doubleValue(), RunReport.read(entry.input("report"))))),
			Map.entry("archsim_engine.sweep.meets", SweepFunctions::meets),
			Map.entry("archsim_engine.sweep.analyze", SweepFunctions::analyze));

	private SweepFunctions() {
	}

	/** {@code (lo, hi)}. */
	private static JsonNode suggestRange(GoldenEntry entry) {
		SweepRange range = SweepRange.suggested(entry.input("currentRps").doubleValue(),
				entry.input("cap").doubleValue());
		return GoldenJson.numbers(List.of(range.fromRps(), range.toRps()));
	}

	private static JsonNode levels(GoldenEntry entry) {
		SweepRange range = new SweepRange(entry.input("fromRps").doubleValue(), entry.input("toRps").doubleValue());
		return GoldenJson.numbers(range.levels(entry.input("steps").intValue()));
	}

	/** {@code meets(row, objectives)}: {@code None} for a step not completed. */
	private static JsonNode meets(GoldenEntry entry) {
		Boolean meets = RecordCodec.read(entry.input("row"), SweepRow.class)
			.meets(StoredObjectives.read(entry.input("objectives")));
		return (meets != null) ? GoldenJson.NODES.booleanNode(meets) : GoldenJson.NODES.nullNode();
	}

	private static JsonNode analyze(GoldenEntry entry) {
		List<SweepRow> rows = new ArrayList<>();
		entry.input("rows").forEach((row) -> rows.add(RecordCodec.read(row, SweepRow.class)));
		return RecordCodec.write(SweepAnalysis.of(rows, StoredObjectives.read(entry.input("objectives"))));
	}

}
