package io.archsim.benchmarks.comparison;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Objective;
import io.archsim.benchmarks.values.PythonValues;

/**
 * A load step's metrics from its repeats: the median of each, robust to one stall. A
 * repeat in which k6 sent requests and none succeeded failed: its latency counts as
 * slower than any measured repeat's, so one failed repeat of three moves the median to
 * the slower of the other two, and two or more leave the step without a latency. A step
 * is unstable when its repeats' p95s differ by more than 2x across the objective.
 */
public final class StepAggregation {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The latencies a failed repeat has none of. */
	private static final List<String> LATENCIES = List.of("p50", "p95", "p99", "mean");

	/** The metrics a step takes the median of, when any repeat has them. */
	private static final List<String> MEDIANS = List.of("p50", "p95", "p99", "mean", "successRate", "throughputRps",
			"cacheHitRatio");

	private static final List<String> QUEUE = List.of("consumedRps", "backlogAtEnd", "p95MessageAgeMs",
			"meanMessageAgeMs");

	/**
	 * How far apart a step's p95s may be across the objective and the step stay stable.
	 */
	private static final double UNSTABLE_RATIO = 2;

	private StepAggregation() {
	}

	/**
	 * Whether a repeat failed: k6 tried to send requests and none succeeded. One in which
	 * k6 sent nothing is the load generator failing, not the system.
	 * @param repeat the repeat
	 * @return whether it failed
	 */
	public static boolean failedRepeat(JsonNode repeat) {
		double attempted = PythonValues.numberOrZero(repeat, "sent") + PythonValues.numberOrZero(repeat, "dropped");
		JsonNode succeeded = PythonValues.get(repeat, "succeeded");
		return !PythonValues.isNone(succeeded) && succeeded.doubleValue() == 0 && attempted > 0;
	}

	/**
	 * A step's metrics from its repeats.
	 * @param repeats the repeats
	 * @return the medians, whether the step is unstable, the repeats, the failed ones and
	 * those k6 dropped requests in, and the containers' resources when the repeats
	 * recorded them
	 */
	public static ObjectNode aggregate(List<? extends JsonNode> repeats) {
		ObjectNode step = NODES.objectNode();
		for (String key : MEDIANS) {
			if (repeats.stream().anyMatch((repeat) -> repeat.has(key))) {
				step.set(key, PythonValues.median(repeats.stream().map((repeat) -> value(repeat, key)).toList()));
			}
		}
		List<JsonNode> queues = repeats.stream()
			.filter((repeat) -> repeat.has("queue"))
			.map((repeat) -> repeat.get("queue"))
			.toList();
		if (!queues.isEmpty()) {
			ObjectNode queue = step.putObject("queue");
			QUEUE.forEach((key) -> queue.set(key,
					PythonValues.median(queues.stream().map((reading) -> PythonValues.get(reading, key)).toList())));
		}
		step.put("unstable", unstable(repeats));
		step.put("repeats", repeats.size());
		step.put("failedRepeats", (int) repeats.stream().filter(StepAggregation::failedRepeat).count());
		step.put("droppedRepeats", (int) repeats.stream().filter(StepAggregation::droppedIn).count());
		RepeatResources.aggregate(repeats).ifPresent((resources) -> step.set("resources", resources));
		return step;
	}

	// k6 had no virtual user free for some of the repeat's requests and dropped them: the
	// load was no longer open-loop, and the repeat's latencies are a lower bound
	private static boolean droppedIn(JsonNode repeat) {
		return PythonValues.numberOrZero(repeat, "dropped") > 0;
	}

	// a failed repeat's p95 is infinite: one failed repeat makes a step unstable, all
	// of them do not
	private static boolean unstable(List<? extends JsonNode> repeats) {
		List<JsonNode> p95s = repeats.stream()
			.map((repeat) -> value(repeat, "p95"))
			.filter((p95) -> !PythonValues.isNone(p95))
			.toList();
		if (p95s.isEmpty()) {
			return false;
		}
		double slowest = PythonValues.max(p95s).doubleValue();
		return slowest > UNSTABLE_RATIO * PythonValues.min(p95s).doubleValue() && slowest > Objective.P95_MS;
	}

	// a failed repeat's latency is infinite, worse than any measured one; runs recorded
	// before that carry k6's zeros instead
	private static JsonNode value(JsonNode repeat, String key) {
		if (LATENCIES.contains(key) && failedRepeat(repeat)) {
			return PythonValues.number(Double.POSITIVE_INFINITY);
		}
		return PythonValues.get(repeat, key);
	}

}
