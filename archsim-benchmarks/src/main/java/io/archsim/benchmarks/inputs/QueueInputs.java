package io.archsim.benchmarks.inputs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * Kafka's inputs: the producer's publish time; the consumer's own time per message, how
 * many partitions it processes at once and what each poll costs beyond its messages.
 */
final class QueueInputs {

	/**
	 * The stack's consumers: one kafkajs consumer processes this many partitions at once.
	 */
	static final int CONSUMERS = 3;

	/** The fewest fetch cycles a pass must record for its fetch overhead to be used. */
	private static final int MIN_POLLS = 50;

	/**
	 * The fetch overhead per poll (ms) of runs calibrated before the service reported
	 * kafkajs's fetch cycle: what the W23 trial measured on the same laptop at 569 req/s.
	 */
	private static final double FALLBACK_MEDIAN = 0.93;

	private static final double FALLBACK_P95 = 3.88;

	private static final int FALLBACK_POLLS = 17577;

	private static final double LEAST_MEDIAN = 0.01;

	private static final double LEAST_P95 = 0.02;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private QueueInputs() {
	}

	/**
	 * Kafka's inputs from the top pass.
	 * @param passes the calibration's passes
	 * @return the inputs
	 */
	static ObjectNode of(JsonNode passes) {
		JsonNode high = passes.get("high");
		JsonNode consumeOwn = PassReadings.timing(high.get("consumer"), "consume_own");
		JsonNode send = PassReadings.timing(high.get("service"), "kafka_send");
		ObjectNode inputs = NODES.objectNode();
		inputs.putObject("publishTimeMs")
			.set("value", lognormal(send))
			.put("how", "producer.send with acks=all on one broker: median " + three(send, "median") + ", p95 "
					+ three(send, "p95") + " ms");
		inputs.putObject("consumerTimeMs")
			.set("value", lognormal(consumeOwn))
			.put("how", "consumer's own time per message (handling minus the INSERT): median "
					+ three(consumeOwn, "median") + ", p95 " + three(consumeOwn, "p95") + " ms");
		inputs.putObject("consumerConcurrency")
			.put("value", CONSUMERS)
			.put("how", "the stack's setting, not measured: one kafkajs consumer processes " + CONSUMERS + " of its "
					+ Designs.KAFKA_PARTITIONS + " partitions at once (partitionsConsumedConcurrently)");
		inputs.set("fetchOverheadMs", fetchOverhead(high.get("consumer")));
		return inputs;
	}

	// what each poll costs beyond processing its messages: per fetch cycle,
	// the cycle less its batches' least span on the consumer's workers
	static ObjectNode fetchOverhead(JsonNode consumer) {
		JsonNode cycle = consumer.path("timings").path("cycle_overhead");
		if (PythonText.truthy(cycle) && cycle.get("count").doubleValue() >= MIN_POLLS) {
			JsonNode fetch = consumer.path("timings").path("cycle_fetch");
			JsonNode after = consumer.path("timings").path("cycle_after");
			return NODES.objectNode()
				.set("value",
						ModelDistributions.lognormal(Math.max(cycle.get("median").doubleValue(), LEAST_MEDIAN),
								Math.max(cycle.get("p95").doubleValue(), LEAST_P95)))
				.put("how", "kafkajs's fetch cycle in the pass (" + PythonText.str(cycle.get("count"))
						+ " polls): each cycle less its batches' least span on " + CONSUMERS + " workers, median "
						+ three(cycle, "median") + ", p95 " + three(cycle, "p95") + " ms (the fetch: median "
						+ PythonFormat.format(medianOrZero(fetch), ".3f") + " ms; the last batch's commit: median "
						+ PythonFormat.format(medianOrZero(after), ".3f") + " ms)");
		}
		return NODES.objectNode()
			.set("value", ModelDistributions.lognormal(FALLBACK_MEDIAN, FALLBACK_P95))
			.put("how",
					"not recorded in this run (the service reports kafkajs's fetch cycle since W23): the W23 "
							+ "trial's top calibration passes on the same laptop (" + FALLBACK_POLLS
							+ " polls), median " + PythonFormat.format(FALLBACK_MEDIAN, ".3f") + ", p95 "
							+ PythonFormat.format(FALLBACK_P95, ".3f") + " ms");
	}

	private static ObjectNode lognormal(JsonNode timing) {
		return ModelDistributions.lognormal(timing.get("median").doubleValue(), timing.get("p95").doubleValue());
	}

	private static String three(JsonNode timing, String key) {
		return PythonFormat.format(timing.get(key).doubleValue(), ".3f");
	}

	private static double medianOrZero(JsonNode timing) {
		return timing.isObject() ? PythonValues.numberOrZero(timing, "median") : 0.0;
	}

}
