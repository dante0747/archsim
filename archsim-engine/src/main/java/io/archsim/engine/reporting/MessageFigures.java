package io.archsim.engine.reporting;

import java.util.List;
import java.util.OptionalDouble;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.runtime.MessageLosses;
import io.archsim.model.component.queue.MessageQueueSpec;

/**
 * A run's figures for its messages, when its design has a queue (report 1.19, ADR-0084):
 * the p95 age of a message when processed at its slowest queue, the messages its measured
 * requests lost by the end of the run (dead-lettered, or dropped when published), and its
 * availability: the share of its measured requests that succeeded and lost no message.
 */
final class MessageFigures {

	private static final String SUMMARY = "summary";

	private MessageFigures() {
	}

	/**
	 * Adds the figures to a run's summary, when its design has a queue.
	 * @param summary the run's summary, which has its measured requests that arrived and
	 * succeeded
	 * @param nodes its parts as the report writes them
	 * @param losses what its measured requests lost of their messages
	 */
	static void write(ObjectNode summary, ArrayNode nodes, MessageLosses losses) {
		List<JsonNode> queues = nodes.valueStream()
			.filter((node) -> MessageQueueSpec.TYPE.equals(node.path("type").stringValue("")))
			.toList();
		if (queues.isEmpty()) {
			return;
		}
		summary.set("messageAgeP95Ms", slowestAge(queues));
		summary.put("lostMessages", losses.messages());
		long succeeded = summary.path("succeeded").asLong(0);
		summary.set("availability", Figures.ratio(succeeded - losses.requests(), summary.path("arrived").asLong(0)));
	}

	// the slowest queue's p95 age; none when a queue held messages it never processed,
	// whose age is unknown
	private static JsonNode slowestAge(List<JsonNode> queues) {
		boolean unaged = queues.stream().map((queue) -> queue.path(SUMMARY)).anyMatch((queue) -> {
			boolean held = queue.path("backlogAtEnd").asLong(0) > 0 || queue.path("arrivals").asLong(0) > 0;
			return held && !queue.path("p95MessageAgeMs").isNumber();
		});
		OptionalDouble slowest = queues.stream()
			.map((queue) -> queue.path(SUMMARY).path("p95MessageAgeMs"))
			.filter(JsonNode::isNumber)
			.mapToDouble(JsonNode::doubleValue)
			.max();
		return (unaged || slowest.isEmpty()) ? Figures.NODES.nullNode()
				: Figures.NODES.numberNode(slowest.getAsDouble());
	}

}
