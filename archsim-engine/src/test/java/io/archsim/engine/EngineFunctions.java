package io.archsim.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

import io.archsim.parity.GoldenEntry;

/**
 * The Python engine's functions this module ports, each as the Java call that replaces
 * it, taking a golden entry's input and giving its output as the golden file records it.
 * Each engine area keeps its own table.
 */
final class EngineFunctions {

	private static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = merged(TextFunctions.PORTED,
			LoadFunctions.PORTED, SeedFunctions.PORTED, SweepFunctions.PORTED);

	private EngineFunctions() {
	}

	/** The Python functions ported here, by qualified name. */
	static Set<String> names() {
		return PORTED.keySet();
	}

	/** The Java counterpart of the entry's function on the entry's input. */
	static JsonNode call(GoldenEntry entry) {
		return Objects.requireNonNull(PORTED.get(entry.fn()), entry.fn()).apply(entry);
	}

	@SafeVarargs
	private static Map<String, Function<GoldenEntry, JsonNode>> merged(
			Map<String, Function<GoldenEntry, JsonNode>>... tables) {
		Map<String, Function<GoldenEntry, JsonNode>> merged = new HashMap<>();
		for (Map<String, Function<GoldenEntry, JsonNode>> table : tables) {
			table.forEach((name, function) -> {
				if (merged.putIfAbsent(name, function) != null) {
					throw new IllegalStateException(name + " is ported twice");
				}
			});
		}
		return Map.copyOf(merged);
	}

}
