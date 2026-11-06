package io.archsim.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.parity.GoldenEntry;
import io.archsim.parity.PythonError;

/**
 * The Python engine's functions this module ports, each as the Java call that replaces
 * it, taking a golden entry's input and giving its output as the golden file records it.
 * Each engine area keeps its own table.
 */
final class EngineFunctions {

	private static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = merged(TextFunctions.PORTED,
			QueueingFunctions.PORTED, LoadFunctions.PORTED, PricingFunctions.PORTED, SeedFunctions.PORTED,
			SweepFunctions.PORTED, SizingFunctions.PORTED, InputFunctions.PORTED, EstimateFunctions.PORTED,
			CostFunctions.PORTED, ReviewFunctions.PORTED, AnalysisFunctions.PORTED, SloFunctions.PORTED,
			ReportFunctions.PORTED, CheckFunctions.PORTED, ExportFunctions.PORTED, ImportDesignFunctions.PORTED);

	private EngineFunctions() {
	}

	/** The Python functions ported here, by qualified name. */
	static Set<String> names() {
		return PORTED.keySet();
	}

	/**
	 * The Java counterpart of the entry's function on the entry's input. The exceptions
	 * the golden files record become the errors the Python engine raises in their place.
	 */
	static JsonNode call(GoldenEntry entry) {
		Function<GoldenEntry, JsonNode> counterpart = Objects.requireNonNull(PORTED.get(entry.fn()), entry.fn());
		try {
			return counterpart.apply(entry);
		}
		catch (DocumentInvalidException ex) {
			ObjectNode error = GoldenJson.NODES.objectNode()
				.put("type", "DocumentInvalidError")
				.put("message", ex.getMessage());
			ex.issues().stream().map(Issue::toJson).forEach(error.putArray("issues")::add);
			throw new PythonError(error, ex);
		}
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
