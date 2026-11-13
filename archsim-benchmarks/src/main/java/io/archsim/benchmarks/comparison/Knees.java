package io.archsim.benchmarks.comparison;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Objective;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * The knee of a load ladder, the highest load that meets the objective, and the steps at
 * or below it.
 */
public final class Knees {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final String RATE = "offeredRps";

	private static final String KNEE = "kneeRps";

	private static final int KNEE_DECIMALS = 1;

	private Knees() {
	}

	/**
	 * The highest load meeting the objective, interpolated between the last step that met
	 * it and the first that did not: linear in p95, or in the success rate when that is
	 * what fails, the lower of the two when both do.
	 * @param steps the ladder's steps, in any order
	 * @return the knee and how it was found: {@code interpolated}, or {@code below} or
	 * {@code above} the ladder
	 */
	public static ObjectNode gridKnee(List<? extends JsonNode> steps) {
		List<JsonNode> ladder = new ArrayList<>(steps);
		ladder.sort((left, right) -> PythonValues.compare(left.get(RATE), right.get(RATE)));
		JsonNode last = null;
		for (JsonNode step : ladder) {
			if (Objective.isMetBy(step)) {
				last = step;
				continue;
			}
			if (last == null) {
				return NODES.objectNode().putNull(KNEE).put("bound", "below").set("belowRps", step.get(RATE));
			}
			return interpolated(last, step);
		}
		ObjectNode above = NODES.objectNode().putNull(KNEE).put("bound", "above");
		return above.set("aboveRps", ladder.isEmpty() ? PythonValues.none() : ladder.getLast().get(RATE));
	}

	// the lower of the crossings that apply: of the p95 when the missed step's was over
	// the objective or unknown, of the success rate when it fell below the objective's
	private static ObjectNode interpolated(JsonNode met, JsonNode missed) {
		List<JsonNode> crossings = new ArrayList<>();
		JsonNode p95 = PythonValues.get(missed, "p95");
		if (PythonValues.isNone(p95) || p95.doubleValue() > Objective.P95_MS) {
			crossings.add(p95Crossing(met, missed));
		}
		if (PythonValues.numberOrZero(missed, "successRate") < Objective.MIN_SUCCESS) {
			crossings.add(successCrossing(met, missed));
		}
		ObjectNode knee = NODES.objectNode().set(KNEE, PythonValues.round(PythonValues.min(crossings), KNEE_DECIMALS));
		knee.put("bound", "interpolated").putArray("between").add(met.get(RATE)).add(missed.get(RATE));
		return knee;
	}

	// where the p95 crosses the objective, linear between the steps;
	// the step that met it when the other's p95 is unknown or no higher
	private static JsonNode p95Crossing(JsonNode met, JsonNode missed) {
		JsonNode p95 = PythonValues.get(missed, "p95");
		double metP95 = met.get("p95").doubleValue();
		if (PythonValues.isNone(p95) || p95.doubleValue() <= metP95) {
			return met.get(RATE);
		}
		return crossing(met, missed, Objective.P95_MS - metP95, p95.doubleValue() - metP95);
	}

	// where the success rate crosses the objective, linear between the steps;
	// the step that met it when the other's is no lower
	private static JsonNode successCrossing(JsonNode met, JsonNode missed) {
		double success = PythonValues.numberOrZero(missed, "successRate");
		double metSuccess = met.get("successRate").doubleValue();
		if (metSuccess <= success) {
			return met.get(RATE);
		}
		return crossing(met, missed, metSuccess - Objective.MIN_SUCCESS, metSuccess - success);
	}

	// from + (to - from) * above / span, multiplied before it is divided, as Python did
	private static JsonNode crossing(JsonNode met, JsonNode missed, double above, double span) {
		double from = met.get(RATE).doubleValue();
		return PythonValues.number(from + (missed.get(RATE).doubleValue() - from) * above / span);
	}

	/**
	 * The steps of a comparison that missed the objective below a higher step that met
	 * it. A stable stack does not get faster with more load, so such a miss was a
	 * disturbance, not the stack's limit, and the run is not a measurement of the stack.
	 * @param rows a comparison's rows, in any order
	 * @return the missed steps' loads, in load order
	 */
	public static List<JsonNode> disturbedSteps(List<? extends JsonNode> rows) {
		List<JsonNode> ladder = new ArrayList<>(rows);
		ladder.sort((left, right) -> PythonValues.compare(left.get(RATE), right.get(RATE)));
		List<Boolean> met = ladder.stream().map((row) -> Objective.isMetBy(row.get("measured"))).toList();
		List<JsonNode> disturbed = new ArrayList<>();
		for (int i = 0; i < ladder.size(); i++) {
			if (!met.get(i) && met.subList(i + 1, met.size()).contains(true)) {
				disturbed.add(ladder.get(i).get(RATE));
			}
		}
		return disturbed;
	}

	/**
	 * The steps at or below a knee: all of them when every step met the objective, none
	 * when none did.
	 * @param steps the steps
	 * @param knee the knee ({@link #gridKnee(List)})
	 * @return the steps at or below it, in their order
	 */
	public static List<JsonNode> atOrBelowKnee(List<? extends JsonNode> steps, JsonNode knee) {
		JsonNode rate = PythonValues.get(knee, KNEE);
		if (!PythonValues.isNone(rate)) {
			return steps.stream()
				.filter((step) -> PythonValues.compare(step.get(RATE), rate) <= 0)
				.map(JsonNode.class::cast)
				.toList();
		}
		return "above".equals(PythonValues.get(knee, "bound").stringValue("")) ? List.copyOf(steps) : List.of();
	}

	/**
	 * The steps at or below both the measured knee and the model's own, where CPU and
	 * memory are compared. Past the model's knee its queue holds every request the real
	 * service would time out, so its CPU reads fully busy and its memory grows without
	 * bound: the knee's error, counted there, not the resource model's.
	 * @param steps the steps
	 * @param measured the measured knee
	 * @param predicted the model's knee on the same steps
	 * @return the steps at or below both, in their order
	 */
	public static List<JsonNode> atOrBelowBothKnees(List<? extends JsonNode> steps, JsonNode measured,
			JsonNode predicted) {
		Set<JsonNode> belowModel = Collections.newSetFromMap(new IdentityHashMap<>());
		belowModel.addAll(atOrBelowKnee(steps, predicted));
		return atOrBelowKnee(steps, measured).stream().filter(belowModel::contains).toList();
	}

	/**
	 * Whether the measured knee fell inside the predicted range. A knee above the ladder
	 * is inside when the range reaches past the top step, one below it when the range
	 * starts below the first.
	 * @param range the predicted range, or None
	 * @param measured the measured knee
	 * @return whether it is inside; empty without a range or a measured knee
	 */
	public static Optional<Boolean> inRange(JsonNode range, JsonNode measured) {
		if (!PythonText.truthy(range)) {
			return Optional.empty();
		}
		double low = range.get("lowRps").doubleValue();
		double high = range.get("highRps").doubleValue();
		JsonNode knee = PythonValues.get(measured, KNEE);
		String bound = PythonValues.get(measured, "bound").stringValue("");
		JsonNode above = PythonValues.get(measured, "aboveRps");
		JsonNode below = PythonValues.get(measured, "belowRps");
		if (!PythonValues.isNone(knee)) {
			return Optional.of(low <= knee.doubleValue() && knee.doubleValue() <= high);
		}
		if ("above".equals(bound) && !PythonValues.isNone(above)) {
			return Optional.of(high >= above.doubleValue());
		}
		if ("below".equals(bound) && !PythonValues.isNone(below)) {
			return Optional.of(low <= below.doubleValue());
		}
		return Optional.empty();
	}

}
