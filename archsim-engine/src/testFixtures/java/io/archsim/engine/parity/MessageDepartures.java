package io.archsim.engine.parity;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.component.queue.MessageQueueSpec;

/**
 * Where the Java departs from the frozen engine on a run whose design has a queue (engine
 * 1.4.0, report 1.19, ADR-0084), and how it is read as the frozen engine's all the same:
 * its summary adds the p95 age of a message at the slowest queue
 * ({@code messageAgeP95Ms}), the messages its measured requests lost by the end of the
 * run ({@code lostMessages}, dead-lettered or dropped) and the share of its measured
 * requests that succeeded and lost no message ({@code availability}). They read as
 * Python's, left out, once the age is the slowest queue's (or none, when a queue held
 * messages it never processed) and the availability lies between the success rate and the
 * success rate less a request for each lost message.
 */
public final class MessageDepartures {

	private static final String SUMMARY = "summary";

	private static final String AGE = "messageAgeP95Ms";

	private static final String LOST = "lostMessages";

	private static final String AVAILABILITY = "availability";

	/** How far a share rounded to 4 decimals may be from the one worked out. */
	private static final double ROUNDING = 5e-5;

	private MessageDepartures() {
	}

	/**
	 * The Java's report with its message figures read as the frozen engine's.
	 * @param java the Java's report, which is not changed
	 * @return a copy of it
	 */
	public static JsonNode asRecorded(JsonNode java) {
		JsonNode summary = java.path(SUMMARY);
		List<JsonNode> queues = java.path("nodes")
			.valueStream()
			.filter((node) -> MessageQueueSpec.TYPE.equals(node.path("type").stringValue("")))
			.toList();
		if (!summary.has(LOST) || queues.isEmpty() || !followFromQueues(summary, queues)) {
			return java;
		}
		JsonNode copy = java.deepCopy();
		((ObjectNode) copy.path(SUMMARY)).remove(List.of(AGE, LOST, AVAILABILITY));
		return copy;
	}

	private static boolean followFromQueues(JsonNode summary, List<JsonNode> queues) {
		long lost = summary.path(LOST).asLong(-1);
		return lost >= 0 && availabilityFollows(summary, lost) && isSlowestAge(summary, queues);
	}

	// no higher than the success rate, and no lower than it less a request for each lost
	// message; none when no request arrived
	private static boolean availabilityFollows(JsonNode summary, long lost) {
		double arrived = summary.path("arrived").asDouble(0);
		JsonNode availability = summary.path(AVAILABILITY);
		if (arrived == 0) {
			return availability.isNull();
		}
		double succeeded = summary.path("succeeded").asDouble(0);
		double share = availability.asDouble(-1);
		return share <= succeeded / arrived + ROUNDING && share >= (succeeded - lost) / arrived - ROUNDING;
	}

	// the slowest queue's age; none when a queue held messages it never processed, or
	// none processed any
	private static boolean isSlowestAge(JsonNode summary, List<JsonNode> queues) {
		boolean unaged = queues.stream().map((queue) -> queue.path(SUMMARY)).anyMatch((queue) -> {
			boolean held = queue.path("backlogAtEnd").asLong(0) > 0 || queue.path("arrivals").asLong(0) > 0;
			return held && !queue.path("p95MessageAgeMs").isNumber();
		});
		double slowest = queues.stream()
			.map((queue) -> queue.path(SUMMARY).path("p95MessageAgeMs"))
			.filter(JsonNode::isNumber)
			.mapToDouble(JsonNode::doubleValue)
			.max()
			.orElse(Double.NaN);
		JsonNode age = summary.path(AGE);
		if (unaged || Double.isNaN(slowest)) {
			return age.isNull();
		}
		return age.isNumber() && age.doubleValue() == slowest;
	}

}
