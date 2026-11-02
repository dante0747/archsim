package io.archsim.model.component.queue;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.component.Port;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.issue.Issue;
import java.util.ArrayList;
import java.util.List;

/**
 * A message queue or stream: producers get an acknowledgement after publishing, and consumers
 * process the messages in the background, calling the queue's outgoing links. Spikes become
 * backlog rather than refusals.
 */
public final class MessageQueueSpec extends ComponentSpec<MessageQueueConfig> {

    public MessageQueueSpec() {
        super(
                "message_queue",
                "Message Queue / Stream",
                Category.MESSAGING,
                MessageQueueConfig.class,
                List.of(
                        Port.in(
                                "in",
                                "Publish",
                                "Messages published by producers (the caller gets an ack, not the result)."),
                        Port.out(
                                "out",
                                "Consumers",
                                "Downstream calls each consumer makes per message, in order, for matching request"
                                        + " types.")));
    }

    @Override
    public List<Issue> validate(NodeSpec node, MessageQueueConfig config, GraphView view) {
        List<Issue> issues = new ArrayList<>();
        String name = node.name();
        if (view.outEdges(node.id()).isEmpty()) {
            issues.add(Issue.warning(
                            "queue.no_consumers",
                            "%s has no consumer links: messages are acknowledged and dropped after the consumer time."
                                    .formatted(name))
                    .atNode(node.id())
                    .withHint("Link the queue's 'Consumers' port to the service that processes messages."));
        }
        int consumers = config.consumers();
        int partitions = config.partitions();
        if (consumers > partitions) {
            issues.add(Issue.warning(
                            "queue.idle_consumers",
                            "%s has %d consumers but only %d partitions; %d consumers stay idle."
                                    .formatted(name, consumers, partitions, consumers - partitions))
                    .atNode(node.id())
                    .withHint("Add partitions or reduce consumers."));
        }
        int slots = config.concurrencyPerConsumer();
        int owned = Math.ceilDiv(partitions, Math.min(consumers, partitions));
        if (slots > 1 && config.partitioned() && slots > owned) {
            issues.add(Issue.warning(
                            "queue.idle_slots",
                            ("%s lets each consumer process %d messages at once, but a consumer owns at most %d"
                                            + " partitions and a partition's messages go one at a time; %d slots per"
                                            + " consumer stay idle.")
                                    .formatted(name, slots, owned, slots - owned))
                    .atNode(node.id())
                    .atPath("consumerConcurrency")
                    .withHint("Add partitions or lower the consumer concurrency."));
        }
        Double memoryGib = config.memoryGib();
        if (config.blocksOnMemoryAlarm() && (memoryGib == null || memoryGib == 0)) {
            issues.add(Issue.warning(
                            "queue.alarm_without_memory",
                            ("%s blocks publishers on a memory alarm, but no memory is set, so the alarm never"
                                            + " fires.")
                                    .formatted(name))
                    .atNode(node.id())
                    .atPath("memoryGib")
                    .withHint("Set the broker's memory and the message size."));
        }
        return issues;
    }
}
