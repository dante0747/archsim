package io.archsim.engine;

import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import io.archsim.engine.report.RunSummary;
import io.archsim.engine.slo.RunFigures;
import io.archsim.engine.slo.SloCheck;
import io.archsim.engine.slo.SloResult;
import io.archsim.engine.slo.StoredObjectives;
import io.archsim.model.codec.RecordCodec;
import io.archsim.parity.GoldenEntry;

/** Service-level objectives: each check, the verdict and its text. */
final class SloFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.slo.slo_checks", SloFunctions::checks),
			Map.entry("archsim_engine.slo.evaluate",
					(entry) -> RecordCodec
						.write(SloResult.evaluate(StoredObjectives.read(entry.input("objectives")), figures(entry)))),
			Map.entry("archsim_engine.slo.verdict_text",
					(entry) -> GoldenJson.text(RecordCodec.read(entry.input("result"), SloResult.class).text())));

	private SloFunctions() {
	}

	private static JsonNode checks(GoldenEntry entry) {
		ArrayNode checks = GoldenJson.NODES.arrayNode();
		SloCheck.each(StoredObjectives.read(entry.input("objectives")), figures(entry))
			.forEach((check) -> checks.add(RecordCodec.write(check)));
		return checks;
	}

	/** The figures of a report's summary. */
	private static RunFigures figures(GoldenEntry entry) {
		return RunFigures.of(RunSummary.read(entry.input("summary")));
	}

}
