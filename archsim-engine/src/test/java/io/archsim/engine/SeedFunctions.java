package io.archsim.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.confidence.ConfidenceLevel;
import io.archsim.engine.seeds.Seeds;
import io.archsim.engine.seeds.ValueRange;
import io.archsim.model.codec.RecordCodec;
import io.archsim.parity.GoldenEntry;

/** Ranges across seeds: a figure's range, the seeds' text and the borderline cap. */
final class SeedFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.seeds.value_range",
					(entry) -> GoldenJson.orNone(ValueRange.of(GoldenJson.readNullableNumbers(entry.input("values")))
						.map(RecordCodec::write))),
			Map.entry("archsim_engine.seeds.range_text", SeedFunctions::rangeText),
			Map.entry("archsim_engine.seeds.seeds_text",
					(entry) -> GoldenJson.text(new Seeds(seeds(entry)).describe())),
			Map.entry("archsim_engine.seeds.cap_confidence", SeedFunctions::capConfidence));

	private SeedFunctions() {
	}

	/**
	 * {@code cap_confidence(level, cap)}: Python takes the levels' names and keeps the
	 * level when there is no cap or either is not a level's name.
	 */
	private static JsonNode capConfidence(GoldenEntry entry) {
		String level = entry.input("level").stringValue();
		Optional<ConfidenceLevel> known = level(level);
		Optional<ConfidenceLevel> cap = entry.input("cap").isNull() ? Optional.empty()
				: level(entry.input("cap").stringValue());
		if (known.isEmpty() || cap.isEmpty()) {
			return GoldenJson.text(level);
		}
		return GoldenJson.text(known.get().cappedAt(cap.get()).json());
	}

	private static Optional<ConfidenceLevel> level(String name) {
		return Arrays.stream(ConfidenceLevel.values()).filter((level) -> level.json().equals(name)).findFirst();
	}

	private static JsonNode rangeText(GoldenEntry entry) {
		JsonNode range = entry.input("r");
		if (range.isNull()) {
			return GoldenJson.NODES.nullNode();
		}
		return GoldenJson.text(RecordCodec.read(range, ValueRange.class).text(entry.input("label").stringValue()));
	}

	private static List<Long> seeds(GoldenEntry entry) {
		List<Long> seeds = new ArrayList<>();
		entry.input("seeds").forEach((seed) -> seeds.add(seed.longValue()));
		return seeds;
	}

}
