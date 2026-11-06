package io.archsim.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import io.archsim.engine.check.CheckResult;
import io.archsim.engine.check.CheckSettings;
import io.archsim.engine.check.CheckText;
import io.archsim.engine.check.FailOn;
import io.archsim.engine.check.Measurement;
import io.archsim.engine.check.Regressions;
import io.archsim.engine.check.Thresholds;
import io.archsim.engine.report.RunReport;
import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.parity.GoldenEntry;

/**
 * {@code archsim check}: what a check measures of a run, the regressions against a
 * baseline, the check's result, its headline and its pull-request comment. The golden
 * files record a measurement with the run's report given and a check with its
 * measurements given, so each is a call on what the Java takes.
 */
final class CheckFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.check.measure", CheckFunctions::measure),
			Map.entry("archsim_engine.check.regressions", CheckFunctions::regressions),
			Map.entry("archsim_engine.check.run_check", CheckFunctions::runCheck),
			Map.entry("archsim_engine.check.headline", CheckFunctions::headline),
			Map.entry("archsim_engine.check.to_markdown", CheckFunctions::toMarkdown));

	private CheckFunctions() {
	}

	/**
	 * {@code measure(document, registry, scenario_id)} with the run's report given: the
	 * design loaded as {@code load_document} loads it, migrated from an older schema.
	 */
	private static JsonNode measure(GoldenEntry entry) {
		return RecordCodec.write(Measurement.of(DesignDocuments.load(entry.input("document")),
				GoldenJson.readNullableText(entry.input("scenarioId")), RunReport.read(entry.input("simulation"))));
	}

	private static JsonNode regressions(GoldenEntry entry) {
		ArrayNode regressions = GoldenJson.NODES.arrayNode();
		Regressions
			.between(measurement(entry.input("current")), measurement(entry.input("baseline")),
					RecordCodec.read(entry.input("thresholds"), Thresholds.class))
			.forEach((regression) -> regressions.add(RecordCodec.write(regression)));
		return regressions;
	}

	/**
	 * {@code run_check(...)} with both measurements given, as the golden files record it.
	 */
	private static JsonNode runCheck(GoldenEntry entry) {
		JsonNode baseline = entry.input("baseline");
		List<FailOn> failOn = new ArrayList<>();
		entry.input("failOn").forEach((mode) -> failOn.add(FailOn.of(mode.asString())));
		CheckSettings settings = new CheckSettings(RecordCodec.read(entry.input("thresholds"), Thresholds.class),
				failOn);
		return RecordCodec.write(CheckResult.of(measurement(entry.input("current")),
				baseline.isNull() ? null : measurement(baseline), settings, List.of()));
	}

	private static JsonNode headline(GoldenEntry entry) {
		CheckResult result = RecordCodec.read(entry.input("result"), CheckResult.class);
		return GoldenJson.text(CheckText.headline(result.current(), result.baseline()));
	}

	private static JsonNode toMarkdown(GoldenEntry entry) {
		return GoldenJson.text(CheckText.markdown(RecordCodec.read(entry.input("result"), CheckResult.class),
				GoldenJson.readNullableText(entry.input("reportUrl"))));
	}

	private static Measurement measurement(JsonNode json) {
		return RecordCodec.read(json, Measurement.class);
	}

}
