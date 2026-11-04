package io.archsim.engine.queueing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The wait in an M/M/c queue. */
class QueueWaitTests {

	@Test
	void mmc_oneServerHalfBusy_waitsHalfTheTime() {
		QueueWait wait = QueueWait.mmc(1, 50, 10).orElseThrow();

		assertThat(wait).isEqualTo(new QueueWait(0.5, new Moments(10.0, 300.0)));
	}

	@Test
	void mmc_noArrivals_neverWaits() {
		QueueWait wait = QueueWait.mmc(16, 0, 5).orElseThrow();

		assertThat(wait).isEqualTo(new QueueWait(0.0, Moments.ZERO));
	}

	@Test
	void mmc_moreArrivalsThanServersServe_isUnstable() {
		assertThat(QueueWait.mmc(1, 100, 10)).isEmpty();
	}

}
