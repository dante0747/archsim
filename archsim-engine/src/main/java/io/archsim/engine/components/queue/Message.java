package io.archsim.engine.components.queue;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.runtime.Request;
import io.archsim.model.document.RequestType;

/**
 * A message in a queue: what it carries (the request that published it, its trace and
 * depth), when its publish started and when it was acknowledged, how often it was
 * delivered, and the lease of the consumer that holds it now.
 */
final class Message {

	private final Request publisher;

	private final double publishedAt;

	private final double createdAt;

	private final int depth;

	private int deliveries;

	private @Nullable Lease lease;

	/**
	 * A message just published.
	 * @param publisher the request that published it, at the queue's depth
	 * @param publishedAt when the publish was acknowledged: its wait in the backlog
	 * starts here
	 * @param createdAt when the publish started: its age starts here
	 */
	Message(Request publisher, double publishedAt, double createdAt) {
		this(publisher, publishedAt, createdAt, publisher.depth());
	}

	private Message(Request publisher, double publishedAt, double createdAt, int depth) {
		this.publisher = publisher;
		this.publishedAt = publishedAt;
		this.createdAt = createdAt;
		this.depth = depth;
	}

	/**
	 * The same message delivered again, as a visibility timeout makes it reappear: its
	 * deliveries so far, held by no consumer.
	 * @return the copy
	 */
	Message reappeared() {
		Message copy = new Message(this.publisher, this.publishedAt, this.createdAt, this.depth);
		copy.deliveries = this.deliveries;
		return copy;
	}

	/**
	 * The request a consumer handles for the message.
	 * @return a request with the publisher's trace, at the depth it was published at
	 */
	Request delivery() {
		return this.publisher.delivered(this.publishedAt, this.depth);
	}

	RequestType operation() {
		return this.publisher.operation();
	}

	int key() {
		return this.publisher.arrival().key();
	}

	double publishedAt() {
		return this.publishedAt;
	}

	double createdAt() {
		return this.createdAt;
	}

	int deliveries() {
		return this.deliveries;
	}

	/**
	 * Counts a delivery that ended.
	 */
	void delivered() {
		this.deliveries++;
	}

	/**
	 * Gives the message to a consumer.
	 * @return the consumer's lease, which a newer delivery replaces
	 */
	Lease lease() {
		Lease taken = new Lease();
		this.lease = taken;
		return taken;
	}

	/**
	 * Whether a consumer still holds the message under a lease.
	 * @param held the lease
	 * @return {@code true} while no later delivery replaced it and it was not returned
	 */
	boolean leasedUnder(Lease held) {
		return this.lease == held;
	}

	/**
	 * Returns a consumer's lease once it is done, unless a later delivery replaced it.
	 * @param held the lease
	 */
	void returnLease(Lease held) {
		if (this.lease == held) {
			this.lease = null;
		}
	}

	/**
	 * A consumer's hold on a message, compared by identity.
	 */
	static final class Lease {

	}

}
