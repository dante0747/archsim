package io.archsim.engine.load;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The hit ratio a TTL cache can expect: Python's values. */
class TtlCacheTrafficTests {

	@Test
	void hitRatio_readsWithoutWrites_hitWithinTheTtl() {
		TtlCacheTraffic traffic = new TtlCacheTraffic(100, 0, 1000, 0.99, 60, null);

		assertThat(traffic.hitRatio()).isCloseTo(0.8908344870198829, within(1e-15));
	}

	@Test
	void hitRatio_aKeyspaceLargerThanTheMemory_missesTheKeysNotHeld() {
		TtlCacheTraffic traffic = new TtlCacheTraffic(1000, 10, 100_000, 0.99, 300, 5000L);

		assertThat(traffic.hitRatio()).isCloseTo(0.7201999067338247, within(1e-15));
	}

	@Test
	void hitRatio_writesInvalidatingKeys_lowerIt() {
		TtlCacheTraffic traffic = new TtlCacheTraffic(50, 50, 10, 0, 1, null);

		assertThat(traffic.hitRatio()).isCloseTo(0.49830981907548466, within(1e-15));
	}

	@Test
	void hitRatio_noReads_isZero() {
		TtlCacheTraffic traffic = new TtlCacheTraffic(0, 10, 1000, 0.99, 60, null);

		assertThat(traffic.hitRatio()).isZero();
	}

}
