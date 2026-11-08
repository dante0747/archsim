package io.archsim.engine.components.queue;

import org.junit.jupiter.api.Test;

import io.archsim.engine.runtime.Request;
import io.archsim.model.document.RequestType;

import static org.assertj.core.api.Assertions.assertThat;

/** A message's deliveries and the lease of the consumer that holds it. */
class MessageTests {

	private final Message message = new Message(
			Request.withoutTrace(new Request.Arrival(7, RequestType.READ, 3, 0, true)), 10, 5);

	@Test
	void leasedUnder_aLaterLease_isFalse() {
		Message.Lease first = this.message.lease();

		this.message.lease();

		assertThat(this.message.leasedUnder(first)).isFalse();
	}

	@Test
	void returnLease_theCurrentLease_releasesIt() {
		Message.Lease lease = this.message.lease();

		this.message.returnLease(lease);

		assertThat(this.message.leasedUnder(lease)).isFalse();
	}

	@Test
	void reappeared_aDeliveredMessage_keepsItsDeliveriesWithoutALease() {
		Message.Lease lease = this.message.lease();
		this.message.delivered();

		Message copy = this.message.reappeared();

		assertThat(copy.deliveries()).isOne();
		assertThat(copy.leasedUnder(lease)).isFalse();
	}

	@Test
	void delivery_aMessage_isTheRequestSentWhenPublished() {
		Request delivery = this.message.delivery();

		assertThat(delivery.arrival().sentAt()).isEqualTo(10.0);
		assertThat(delivery.arrival().id()).isEqualTo(7);
	}

}
