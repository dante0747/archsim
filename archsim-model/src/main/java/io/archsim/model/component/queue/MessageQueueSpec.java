package io.archsim.model.component.queue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentIdentity;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.component.Port;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.issue.Issue;

/**
 * A message queue or stream: producers get an acknowledgement after publishing, and
 * consumers process the messages in the background, calling the queue's outgoing links.
 * Spikes become backlog rather than refusals.
 */
public final class MessageQueueSpec extends ComponentSpec<MessageQueueConfig> {

	/**
	 * Creates the message queue type: messages are published in, and consumers call out.
	 */
	public MessageQueueSpec() {
		super(new ComponentIdentity("message_queue", "Message Queue / Stream", Category.MESSAGING),
				MessageQueueConfig.class,
				List.of(Port.in("in", "Publish",
						"Messages published by producers (the caller gets an ack, not the result)."),
						Port.out("out", "Consumers",
								"Downstream calls each consumer makes per message, in order, for matching request"
										+ " types.")));
	}

	@Override
	public Map<String, TechnologyProfile> technologies() {
		return MessageQueueConfig.PROFILES.byName();
	}

	@Override
	public List<Issue> validate(NodeSpec node, MessageQueueConfig config, GraphView view) {
		List<Issue> issues = new ArrayList<>();
		noConsumers(node, view).ifPresent(issues::add);
		idleConsumers(node, config).ifPresent(issues::add);
		idleSlots(node, config).ifPresent(issues::add);
		alarmWithoutMemory(node, config).ifPresent(issues::add);
		return issues;
	}

	private static Optional<Issue> noConsumers(NodeSpec node, GraphView view) {
		if (!view.outEdges(node.id()).isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(Issue
			.warning("queue.no_consumers",
					"%s has no consumer links: messages are acknowledged and dropped after the consumer time."
						.formatted(node.name()))
			.atNode(node.id())
			.withHint("Link the queue's 'Consumers' port to the service that processes messages."));
	}

	private static Optional<Issue> idleConsumers(NodeSpec node, MessageQueueConfig config) {
		int consumers = config.consumers();
		int partitions = config.partitions();
		if (consumers <= partitions) {
			return Optional.empty();
		}
		return Optional.of(Issue
			.warning("queue.idle_consumers",
					"%s has %d consumers but only %d partitions; %d consumers stay idle.".formatted(node.name(),
							consumers, partitions, consumers - partitions))
			.atNode(node.id())
			.withHint("Add partitions or reduce consumers."));
	}

	private static Optional<Issue> idleSlots(NodeSpec node, MessageQueueConfig config) {
		int slots = config.concurrencyPerConsumer();
		int partitions = config.partitions();
		int owned = Math.ceilDiv(partitions, Math.min(config.consumers(), partitions));
		if (slots <= 1 || !config.partitioned() || slots <= owned) {
			return Optional.empty();
		}
		return Optional.of(Issue
			.warning("queue.idle_slots",
					("%s lets each consumer process %d messages at once, but a consumer owns at most %d"
							+ " partitions and a partition's messages go one at a time; %d slots per"
							+ " consumer stay idle.")
						.formatted(node.name(), slots, owned, slots - owned))
			.atNode(node.id())
			.atPath("consumerConcurrency")
			.withHint("Add partitions or lower the consumer concurrency."));
	}

	private static Optional<Issue> alarmWithoutMemory(NodeSpec node, MessageQueueConfig config) {
		Double memoryGib = config.memoryGib();
		boolean memorySet = memoryGib != null && memoryGib != 0;
		if (!config.blocksOnMemoryAlarm() || memorySet) {
			return Optional.empty();
		}
		return Optional.of(
				Issue
					.warning("queue.alarm_without_memory",
							("%s blocks publishers on a memory alarm, but no memory is set, so the alarm never"
									+ " fires.")
								.formatted(node.name()))
					.atNode(node.id())
					.atPath("memoryGib")
					.withHint("Set the broker's memory and the message size."));
	}

}
