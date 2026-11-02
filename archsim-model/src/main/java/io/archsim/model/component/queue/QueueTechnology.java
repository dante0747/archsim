package io.archsim.model.component.queue;

import io.archsim.model.codec.JsonEnum;

/**
 * The messaging product: a log (Kafka), a broker (RabbitMQ) or a managed queue (SQS,
 * Pub/Sub).
 */
public enum QueueTechnology implements JsonEnum {

	/** Apache Kafka, a partitioned log. */
	KAFKA("kafka"),
	/** RabbitMQ, a message broker. */
	RABBITMQ("rabbitmq"),
	/** Amazon SQS, a managed queue. */
	SQS("sqs"),
	/** Google Cloud Pub/Sub, a managed messaging service. */
	PUBSUB("pubsub"),
	/** NATS, a lightweight messaging system. */
	NATS("nats"),
	/** Redis Streams, an append-only log in Redis. */
	REDIS_STREAMS("redis_streams"),
	/** A queue of no particular product. */
	GENERIC("generic");

	private final String json;

	QueueTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
