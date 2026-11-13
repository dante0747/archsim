package io.archsim.benchmarks.inputs;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * The model inputs from a calibration's replicate sets. Each input is taken whole from
 * the replicate whose value is the median, so a distribution's median and p95 come from
 * the same pass and its formula is that pass's; a disturbed pass falls to the edge and
 * drops out. Records without a value (the low-load reference, the anchor) take each
 * number's median. An input only some replicates have is the median of those that have
 * it: a pass without a mark-compact gives no {@code gcEveryRequests}, and before the v6
 * campaign a first replicate without one dropped it altogether, so the model spaced the
 * median pause by the runtime profile's default. The spacing is left out when the median
 * pause is 0.
 */
final class MedianInputs {

	private static final int DECIMALS = 4;

	private static final List<String> ORDINALS = List.of("first", "second", "third", "fourth", "fifth");

	private static final List<String> PRIMARY_KEYS = List.of("median", "mean", "value");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private MedianInputs() {
	}

	/**
	 * The median inputs.
	 * @param replicates each replicate's inputs
	 * @return the inputs, in the order the replicates first name them
	 */
	static ObjectNode of(List<ObjectNode> replicates) {
		Set<String> keys = new LinkedHashSet<>();
		replicates.forEach((replicate) -> keys.addAll(replicate.propertyNames()));
		ObjectNode median = NODES.objectNode();
		for (String key : keys) {
			List<Integer> having = new ArrayList<>();
			for (int index = 0; index < replicates.size(); index++) {
				if (replicates.get(index).has(key)) {
					having.add(index);
				}
			}
			List<JsonNode> items = having.stream().map((index) -> replicates.get(index).get(key)).toList();
			JsonNode first = items.getFirst();
			median.set(key,
					(first.isObject() && first.has("value")) ? picked(items, having, replicates.size()) : merge(items));
		}
		if (median.has("gcEveryRequests") && !PythonText.truthy(median.path("gcPauseMs").path("value"))) {
			// no pause at the median: nothing to space out
			median.remove("gcEveryRequests");
		}
		return median;
	}

	/**
	 * The number an input is ranked by across replicates: itself, or a distribution's
	 * median (its mean when it has no median).
	 * @param value the input's value
	 * @return the number, or {@code null} for none
	 */
	static @Nullable Double primary(JsonNode value) {
		if (value.isNumber()) {
			return value.doubleValue();
		}
		for (String key : PRIMARY_KEYS) {
			if (value.path(key).isNumber()) {
				return value.get(key).doubleValue();
			}
		}
		return null;
	}

	// the input of the replicate whose value is the median, and which one it was
	private static ObjectNode picked(List<JsonNode> items, List<Integer> having, int replicates) {
		List<Integer> ranked = new ArrayList<>();
		for (int index = 0; index < items.size(); index++) {
			if (primary(items.get(index).get("value")) != null) {
				ranked.add(index);
			}
		}
		ranked.sort((left, right) -> PythonValues.compare(rank(items.get(left)), rank(items.get(right))));
		int pick = ranked.isEmpty() ? 0 : ranked.get((ranked.size() - 1) / 2);
		JsonNode chosen = items.get(pick);
		String of = (items.size() == replicates) ? replicates + " replicates"
				: "the " + items.size() + " of " + replicates + " replicates with it";
		ObjectNode input = ((ObjectNode) chosen).deepCopy();
		input.put("how", "the median of " + of + " (" + ordinal(having.get(firstEqual(items, pick))) + "): "
				+ chosen.get("how").stringValue());
		return input;
	}

	// the first item equal to the one picked, as Python's list.index finds it: the
	// picked one itself at the latest, which it is even when it holds a NaN
	private static int firstEqual(List<JsonNode> items, int pick) {
		int index = 0;
		while (index < pick && !PythonValues.equal(items.get(index), items.get(pick))) {
			index++;
		}
		return index;
	}

	// each number's median over the replicates, objects key by key,
	// anything else the first's
	private static JsonNode merge(List<JsonNode> values) {
		JsonNode first = values.getFirst();
		if (first.isObject()) {
			ObjectNode merged = NODES.objectNode();
			for (String key : first.propertyNames()) {
				merged.set(key, merge(
						values.stream().filter((value) -> value.has(key)).map((value) -> value.get(key)).toList()));
			}
			return merged;
		}
		if (!first.isNumber()) {
			return first;
		}
		JsonNode median = PythonValues.median(values.stream().filter(JsonNode::isNumber).toList());
		return median.isFloatingPointNumber() ? PythonValues.round(median, DECIMALS) : median;
	}

	// what an input is ranked by: its primary number, 0 for none, compared as Python
	// compares floats (-0.0 is 0.0)
	private static JsonNode rank(JsonNode item) {
		Double primary = primary(item.get("value"));
		return PythonValues.number((primary != null) ? primary : 0.0);
	}

	private static String ordinal(int index) {
		return (index < ORDINALS.size()) ? ORDINALS.get(index) : "number " + (index + 1);
	}

}
