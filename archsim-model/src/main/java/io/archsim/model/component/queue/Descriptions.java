package io.archsim.model.component.queue;

import io.archsim.model.component.Resources;

/**
 * What each setting of a message queue means, as the editor's inspector and the component
 * catalog show it.
 */
final class Descriptions {

	static final String CAPACITY_GROUP = "Capacity";

	static final String COST_GROUP = "Cost";

	static final String PERFORMANCE_GROUP = "Performance";

	static final String RELIABILITY_GROUP = "Reliability";

	static final String RESOURCES_GROUP = "Resources";

	static final String TECHNOLOGY_BEHAVIOUR_GROUP = "Technology behaviour";

	static final String TECHNOLOGY_GROUP = "Technology";

	static final String TECHNOLOGY = "The broker or managed service. It sets how messages are partitioned, "
			+ "acknowledged and redelivered, shown under Technology behaviour. Presets also fill in typical "
			+ "numbers.";

	static final String PARTITIONS = "Partitions (Kafka) or shards: at most one consumer works per partition, so "
			+ "effective consumers = min(consumers, partitions). Set it high for brokers without partitions.";

	static final String CONSUMERS = "Concurrent consumers processing messages (consumer instances x threads).";

	static final String MAX_DEPTH = "Messages the queue holds; publishes beyond it are rejected (queue_full), or "
			+ "dropped silently with at-most-once delivery. Empty = unbounded.";

	static final String PUBLISH_TIME_MS = "Time for the broker to accept a publish (ms), before any replication wait "
			+ "or batching.";

	static final String CONSUMER_TIME_MS = "Consumer handling time per message before its downstream calls (ms).";

	static final String CONSUMER_CONCURRENCY = "Messages one consumer processes at once (kafkajs "
			+ "partitionsConsumedConcurrently, a consumer's worker threads); a partition's messages still go one "
			+ "at a time. Set, consumers poll in cycles (see fetch overhead). Empty = one at a time.";

	static final String FETCH_OVERHEAD_MS = "A consumer's time per poll beyond processing its messages: the fetch "
			+ "round trip and the offset commit (ms). Set, a consumer polls in cycles: it takes its partitions' "
			+ "whole backlog (a shared queue: one message per slot), processes it, pays this, then polls again, "
			+ "so messages that arrive meanwhile wait for the next poll. Empty = each message is taken as soon as "
			+ "a consumer is free.";

	static final String MAX_DELIVERIES = "Delivery attempts per message; after the last failure it goes to the "
			+ "dead-letter queue.";

	static final String PARTITIONING = "key: messages with the same key go to the same partition and are consumed in "
			+ "order by its one consumer (Kafka). round_robin: spread evenly, still one consumer per partition. "
			+ "none: one shared queue with competing consumers (RabbitMQ, SQS).";

	static final String ACKS = "all: the publish waits until the replicas have the message (Kafka acks=all, the "
			+ "default since 3.0).";

	static final String REPLICATION_MS = "Extra publish time waiting for replicas when acks = all (ms).";

	static final String LINGER_MS = "The producer holds each message up to this long to batch it (Kafka linger.ms); a "
			+ "publish waits a uniform share of it. Empty = technology default.";

	static final String DELIVERY = "at_least_once: a full backlog rejects publishes. at_most_once (core NATS): the "
			+ "overflow is dropped silently and the publisher still gets an ack.";

	static final String VISIBILITY_TIMEOUT_S = "A message being processed longer than this is delivered again to "
			+ "another consumer (SQS visibility timeout, Pub/Sub ack deadline). Empty = technology default (SQS "
			+ "30 s, Pub/Sub 10 s, none for brokers with consumer acks).";

	static final String FIFO = "FIFO queue (SQS): ordered, exactly-once, and limited to the publish rate below.";

	static final String FIFO_LIMIT_RPS = "Publishes per second a FIFO queue accepts (SQS: 300 without batching, 3,000 "
			+ "with); the excess is throttled.";

	static final String FLOW_CONTROL = "Block publishers while queued messages use more memory than the high "
			+ "watermark (RabbitMQ memory alarm). Needs memory set. Auto: on for RabbitMQ.";

	static final String MEMORY_HIGH_WATERMARK = "Share of the broker's memory queued messages may use before "
			+ "publishers are blocked.";

	static final String MESSAGE_SIZE_KB = "Average message size in memory, with overhead (KiB). Used by the memory "
			+ "alarm.";

	static final String BROKERS = "Broker nodes in the cluster (used for cost; throughput is set by consumers and "
			+ "partitions).";

	static final String CPU_CORES = Resources.CPU_PER + "broker" + Resources.NOT_MODELLED_WHEN_EMPTY
			+ Resources.PRICED_ONLY;

	static final String MEMORY_GIB = Resources.MEMORY_PER + "broker" + Resources.IN_GIB
			+ Resources.NOT_MODELLED_WHEN_EMPTY
			+ "Used by the memory alarm (RabbitMQ); otherwise shown on the design and priced.";

	static final String STORAGE_GIB = Resources.STORAGE_PER + "broker" + Resources.IN_GIB
			+ Resources.NOT_MODELLED_WHEN_EMPTY + Resources.PRICED_ONLY;

	static final String INSTANCE_TYPE = Resources.INSTANCE_TYPE_OF_EACH + "broker" + Resources.INSTANCE_TYPE_USE;

	static final String COST_PER_HOUR = "Price of one broker per hour, in USD. Illustrative default; use your "
			+ "provider's pricing.";

	private Descriptions() {
	}

}
