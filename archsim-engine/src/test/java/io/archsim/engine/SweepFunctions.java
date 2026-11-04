package io.archsim.engine;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.sweep.SweepRange;
import io.archsim.parity.GoldenEntry;

/** Load sweeps: their default range, the highest load a step can run, and the levels. */
final class SweepFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.sweep.suggest_range", SweepFunctions::suggestRange),
			Map.entry("archsim_engine.sweep.rps_cap",
					(entry) -> GoldenJson
						.number(SweepRange.rpsCap(SimulationLimits.DEFAULT, entry.input("durationS").doubleValue()))),
			Map.entry("archsim_engine.sweep.levels", SweepFunctions::levels));

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

}
