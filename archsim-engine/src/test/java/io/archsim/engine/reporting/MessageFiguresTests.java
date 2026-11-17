package io.archsim.engine.reporting;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.runtime.MessageLosses;
import io.archsim.engine.runtime.RequestFate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run's figures for its messages (ADR-0084): the slowest queue's p95 age, the messages
 * its queues lost, and its availability counting the requests that lost one.
 */
class MessageFiguresTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void write_severalQueues_givesTheSlowestQueuesAgeAndTheMessagesLost() {
		ObjectNode summary = summary(100, 100);

		MessageFigures.write(summary, nodes(queue(500.0, 0), queue(2000.0, 0)), losses(6, 2));

		assertThat(summary.path("messageAgeP95Ms").doubleValue()).isEqualTo(2000.0);
		assertThat(summary.path("lostMessages").longValue()).isEqualTo(6);
	}

	@Test
	void write_requestsThatLostAMessage_countAgainstTheAvailability() {
		ObjectNode summary = summary(100, 99);

		MessageFigures.write(summary, nodes(queue(500.0, 0)), losses(5, 4));

		assertThat(summary.path("availability").doubleValue()).isEqualTo(0.95);
	}

	@Test
	void write_aQueueThatProcessedNothingButHeldMessages_leavesTheAgeUndecided() {
		ObjectNode summary = summary(100, 100);

		MessageFigures.write(summary, nodes(queue(500.0, 0), queue(null, 40)), losses(0, 0));

		assertThat(summary.path("messageAgeP95Ms").isNull()).isTrue();
	}

	@Test
	void write_aQueueThatGotMessagesButProcessedNone_leavesTheAgeUndecided() {
		ObjectNode summary = summary(100, 100);
		ObjectNode stalled = queue(null, 0);
		((ObjectNode) stalled.required("summary")).put("arrivals", 25);

		MessageFigures.write(summary, nodes(queue(500.0, 0), stalled), losses(0, 0));

		assertThat(summary.path("messageAgeP95Ms").isNull()).isTrue();
	}

	@Test
	void write_noRequestArrived_hasNoAvailability() {
		ObjectNode summary = summary(0, 0);

		MessageFigures.write(summary, nodes(queue(null, 0)), losses(0, 0));

		assertThat(summary.path("availability").isNull()).isTrue();
		assertThat(summary.path("messageAgeP95Ms").isNull()).isTrue();
	}

	@Test
	void write_noQueue_addsNothing() {
		ObjectNode summary = summary(100, 100);

		MessageFigures.write(summary, JSON.createArrayNode(), losses(0, 0));

		assertThat(summary.has("lostMessages")).isFalse();
	}

	/**
	 * What the measured requests lost.
	 * @param messages the messages lost
	 * @param requests the requests that succeeded but lost one, each its first message
	 * @return the tally
	 */
	private static MessageLosses losses(int messages, int requests) {
		MessageLosses losses = new MessageLosses();
		for (int lost = 0; lost < messages; lost++) {
			RequestFate fate = new RequestFate(losses, true);
			if (lost < requests) {
				fate.succeeded();
			}
			fate.lostAMessage();
		}
		return losses;
	}

	private static ObjectNode summary(long arrived, long succeeded) {
		return JSON.createObjectNode().put("arrived", arrived).put("succeeded", succeeded);
	}

	private static ArrayNode nodes(ObjectNode... queues) {
		ArrayNode nodes = JSON.createArrayNode();
		for (ObjectNode queue : queues) {
			nodes.add(queue);
		}
		return nodes;
	}

	/**
	 * A queue as the report writes it.
	 * @param ageMs its p95 message age; {@code null} when it processed nothing
	 * @param backlogAtEnd the messages it still held at the end
	 * @return its node
	 */
	private static ObjectNode queue(@Nullable Double ageMs, long backlogAtEnd) {
		ObjectNode queue = JSON.createObjectNode().put("type", "message_queue");
		queue.putObject("summary").put("backlogAtEnd", backlogAtEnd).put("p95MessageAgeMs", ageMs);
		return queue;
	}

}
