package io.archsim.benchmarks.comparison;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Measured repeats and load steps for the tests, as the harness records them.
 */
final class Repeats {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final int SENT = 1000;

	private static final double WINDOW_S = 20;

	private Repeats() {
	}

	/**
	 * One measured repeat of 1,000 requests; {@code p95} null is a failed repeat.
	 */
	static ObjectNode repeat(@Nullable Double p95) {
		return repeat(p95, SENT);
	}

	static ObjectNode repeat(@Nullable Double p95, int sent) {
		int succeeded = (p95 != null) ? sent : 0;
		ObjectNode repeat = NODES.objectNode();
		latency(repeat, "p50", p95, 0.5);
		latency(repeat, "p95", p95, 1);
		latency(repeat, "p99", p95, 2);
		latency(repeat, "mean", p95, 0.5);
		return repeat.put("sent", sent)
			.put("dropped", 0)
			.put("failed", sent - succeeded)
			.put("succeeded", succeeded)
			.put("successRate", (double) succeeded / sent)
			.put("throughputRps", succeeded / WINDOW_S);
	}

	/**
	 * A failed repeat as run 4 recorded it, with k6's zeros for its latencies.
	 */
	static ObjectNode zeros() {
		return repeat(null).put("p50", 0).put("p95", 0).put("p99", 0).put("mean", 0);
	}

	/**
	 * A ladder step the knee is found on: its offered load, p95 and success rate.
	 */
	static ObjectNode step(int offeredRps, @Nullable Double p95, double successRate) {
		ObjectNode step = NODES.objectNode().put("offeredRps", offeredRps);
		if (p95 == null) {
			step.putNull("p95");
		}
		else {
			step.put("p95", p95);
		}
		return step.put("successRate", successRate);
	}

	static ObjectNode step(int offeredRps, double p95) {
		return step(offeredRps, p95, 1.0);
	}

	private static void latency(ObjectNode repeat, String name, @Nullable Double p95, double factor) {
		if (p95 == null) {
			repeat.putNull(name);
		}
		else {
			repeat.put(name, p95 * factor);
		}
	}

}
