package io.archsim.engine.components.queue;

/**
 * Where a queue's messages wait for a consumer, and how its consumers take them:
 * partitions, each owned by one consumer; one shared queue its consumers poll in cycles;
 * or one shared store each consumer takes the next message from.
 */
sealed interface Holding permits Partitions, SharedQueue, SharedStore {

	/**
	 * Puts a message where its consumer finds it, waking a consumer that waits for one.
	 * @param message the message
	 * @param now the time
	 */
	void add(Message message, double now);

	/**
	 * Starts the queue's consumers, each a routine of its own.
	 * @param consuming how they process what they take
	 */
	void startConsumers(Consuming consuming);

}
