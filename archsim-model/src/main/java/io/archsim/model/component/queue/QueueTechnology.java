package io.archsim.model.component.queue;

import io.archsim.model.codec.JsonEnum;

/** The messaging product: a log (Kafka), a broker (RabbitMQ) or a managed queue (SQS, Pub/Sub). */
public enum QueueTechnology implements JsonEnum {
    KAFKA("kafka"),
    RABBITMQ("rabbitmq"),
    SQS("sqs"),
    PUBSUB("pubsub"),
    NATS("nats"),
    REDIS_STREAMS("redis_streams"),
    GENERIC("generic");

    private final String json;

    QueueTechnology(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
