package io.archsim.benchmarks.load;

import org.junit.jupiter.api.Test;

import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The virtual users k6 holds ready for a traffic: as many as a whole 2 s timeout's
 * arrivals up to 1,000, as many as a quarter of a second's above that, fifty more, and
 * 3,000 at most; and how many seconds of arrivals may then wait at once.
 */
class TrafficTests {

	// 250 req/s: 500 requests may wait for their 2 s timeout
	@Test
	void virtualUsers_aRateWhoseTimeoutNeedsUnderAThousand_coverTheWholeTimeout() {
		assertThat(traffic(250).virtualUsers()).isEqualTo(550);
	}

	// 2,000 req/s: its timeout would need 4,000, a quarter of a second 500
	@Test
	void virtualUsers_aRateWhoseTimeoutNeedsMore_stayAtAThousand() {
		assertThat(traffic(2000).virtualUsers()).isEqualTo(1050);
	}

	@Test
	void virtualUsers_aRateWhoseQuarterSecondNeedsOverAThousand_coverAQuarterOfASecond() {
		assertThat(traffic(8000).virtualUsers()).isEqualTo(2050);
	}

	// 4,001 req/s: 1,000.25 at once, so 1,001
	@Test
	void virtualUsers_aRateWithAQuarterOfAUserOver_roundUp() {
		assertThat(traffic(4001).virtualUsers()).isEqualTo(1051);
	}

	@Test
	void virtualUsers_aRateThatWouldNeedMore_areThreeThousandAtMost() {
		assertThat(traffic(20000).virtualUsers()).isEqualTo(3000);
	}

	@Test
	void coveredSeconds_aRateWhoseTimeoutIsCovered_isTheTimeout() {
		assertThat(traffic(250).coveredSeconds()).isEqualTo(2.0);
	}

	// 1,000 waiting at 2,000 req/s
	@Test
	void coveredSeconds_aRateAtAThousandUsers_isWhatTheyHold() {
		assertThat(traffic(2000).coveredSeconds()).isEqualTo(0.5);
	}

	@Test
	void coveredSeconds_aRateCoveredForAQuarterOfASecond_isAQuarterOfASecond() {
		assertThat(traffic(8000).coveredSeconds()).isEqualTo(0.25);
	}

	// 2,950 waiting at 29,500 req/s
	@Test
	void coveredSeconds_aRateAtTheMostUsers_isLessThanAQuarterOfASecond() {
		assertThat(traffic(29500).coveredSeconds()).isEqualTo(0.1);
	}

	private static Traffic traffic(double rate) {
		return Traffic.of(Experiments.standard().get("pg-mixed"), "read:0.9,write:0.1", rate);
	}

}
