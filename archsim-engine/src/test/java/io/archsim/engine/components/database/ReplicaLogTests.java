package io.archsim.engine.components.database;

import org.junit.jupiter.api.Test;

import io.archsim.engine.kernel.Simulation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A read replica's replay of its primary's writes, as the Python engine 0.16.0 tracks it.
 */
class ReplicaLogTests {

	private final ReplicaLog log = new ReplicaLog(new Simulation());

	@Test
	void replayed_theNextWriteInOrder_isApplied() {
		this.log.expect(1, 0);

		this.log.replayed(1);

		assertThat(this.log.applied()).isOne();
	}

	@Test
	void replayed_aWriteAheadOfAnEarlierOne_waitsForIt() {
		this.log.expect(1, 0);
		this.log.expect(2, 5);

		this.log.replayed(2);

		assertThat(this.log.applied()).isZero();
	}

	@Test
	void replayed_theEarlierWriteAfterALaterOne_appliesBoth() {
		this.log.expect(1, 0);
		this.log.expect(2, 5);
		this.log.replayed(2);

		this.log.replayed(1);

		assertThat(this.log.applied()).isEqualTo(2);
	}

	@Test
	void lag_writesWaiting_isTheNextInOrdersAge() {
		this.log.expect(1, 10);
		this.log.expect(2, 4);

		assertThat(this.log.lag(30)).isEqualTo(20.0);
	}

	@Test
	void lag_theNextInOrderNotWaiting_isTheOldestWaitingsAge() {
		this.log.expect(3, 12);
		this.log.expect(2, 8);

		assertThat(this.log.lag(30)).isEqualTo(22.0);
	}

	@Test
	void lag_nothingWaiting_isZero() {
		assertThat(this.log.lag(30)).isZero();
	}

	@Test
	void behind_aWriteWaiting_isTrue() {
		this.log.expect(1, 0);

		assertThat(this.log.behind()).isTrue();
	}

}
