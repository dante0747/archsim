package io.archsim.engine;

import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.provenance.ValueText;
import io.archsim.engine.report.ReportDigest;
import io.archsim.engine.text.HalfUp;
import io.archsim.engine.text.NumberText;
import io.archsim.parity.GoldenEntry;

/** How the engine writes numbers and values as text, and the digest of a report. */
final class TextFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.explain.num",
					(entry) -> GoldenJson
						.text(NumberText.formula(GoldenJson.readNullableNumber(entry.input("v")), digits(entry)))),
			Map.entry("archsim_engine.explain.pct",
					(entry) -> GoldenJson.text(NumberText.percent(GoldenJson.readNullableNumber(entry.input("v"))))),
			Map.entry("archsim_engine.load.js_num",
					(entry) -> GoldenJson.text(NumberText.plain(GoldenJson.readNumber(entry.input("x"))))),
			Map.entry("archsim_engine.load.fmt_int",
					(entry) -> GoldenJson.text(HalfUp.grouped(GoldenJson.readNumber(entry.input("x"))))),
			Map.entry("archsim_engine.load.fixed",
					(entry) -> GoldenJson.text(HalfUp.fixed(GoldenJson.readNumber(entry.input("x")), digits(entry)))),
			Map.entry("archsim_engine.load.round_half_up",
					(entry) -> GoldenJson.number(HalfUp.round(GoldenJson.readNumber(entry.input("x")), digits(entry)))),
			Map.entry("archsim_engine.sizing.fmt",
					(entry) -> GoldenJson.text(NumberText.brief(GoldenJson.readNumber(entry.input("v"))))),
			Map.entry("archsim_engine.seeds.fmt_ms",
					(entry) -> GoldenJson.text(NumberText.milliseconds(GoldenJson.readNumber(entry.input("v"))))),
			Map.entry("archsim_engine.provenance.describe_value",
					(entry) -> GoldenJson
						.text(ValueText.describe(entry.input("v"), GoldenJson.readNullableText(entry.input("unit"))))),
			Map.entry("archsim_engine.report.canonical_digest",
					(entry) -> GoldenJson.text(ReportDigest.of(entry.input("obj")))));

	private TextFunctions() {
	}

	private static int digits(GoldenEntry entry) {
		return entry.input("digits").intValue();
	}

}
