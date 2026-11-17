package io.archsim.engine.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What became of a user request beyond its answer (ADR-0084): a measured request that
 * succeeded and lost a message counts once, whichever came first.
 */
class RequestFateTests {

	private final MessageLosses losses = new MessageLosses();

	@Test
	void lostAMessage_afterTheRequestSucceeded_countsItOnce() {
		RequestFate fate = new RequestFate(this.losses, true);

		fate.succeeded();
		fate.lostAMessage();

		assertThat(this.losses.requests()).isEqualTo(1);
	}

	@Test
	void succeeded_afterAMessageWasLost_countsItOnce() {
		RequestFate fate = new RequestFate(this.losses, true);

		fate.lostAMessage();
		fate.succeeded();

		assertThat(this.losses.requests()).isEqualTo(1);
	}

	@Test
	void lostAMessage_severalMessagesOfOneRequest_countOnce() {
		RequestFate fate = new RequestFate(this.losses, true);
		fate.succeeded();

		fate.lostAMessage();
		fate.lostAMessage();

		assertThat(this.losses.requests()).isEqualTo(1);
	}

	@Test
	void lostAMessage_severalMessagesOfOneRequest_countEachMessage() {
		RequestFate fate = new RequestFate(this.losses, true);

		fate.lostAMessage();
		fate.lostAMessage();

		assertThat(this.losses.messages()).isEqualTo(2);
	}

	@Test
	void lostAMessage_aRequestThatDidNotSucceed_countsItsMessageButNotTheRequest() {
		RequestFate fate = new RequestFate(this.losses, true);

		fate.lostAMessage();

		assertThat(this.losses.requests()).isZero();
		assertThat(this.losses.messages()).isEqualTo(1);
	}

	@Test
	void lostAMessage_aRequestOutsideTheWindow_countsNothing() {
		RequestFate fate = new RequestFate(this.losses, false);
		fate.succeeded();

		fate.lostAMessage();

		assertThat(this.losses.requests()).isZero();
		assertThat(this.losses.messages()).isZero();
	}

}
