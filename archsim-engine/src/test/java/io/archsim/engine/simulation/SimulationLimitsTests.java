package io.archsim.engine.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run's request limit holds for any count asked of it: one that is not a number, or has
 * no end, is over the limit too.
 */
class SimulationLimitsTests {

	private static final SimulationLimits LIMITS = new SimulationLimits(1_000, 5.0);

	@Test
	void allowsRequests_aCountAtTheLimit_isTrue() {
		assertThat(LIMITS.allowsRequests(1_000.0)).isTrue();
	}

	@Test
	void allowsRequests_aCountAboveTheLimit_isFalse() {
		assertThat(LIMITS.allowsRequests(1_000.5)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(doubles = { Double.NaN, Double.POSITIVE_INFINITY })
	void allowsRequests_aCountThatIsNotFinite_isFalse(double expectedRequests) {
		assertThat(LIMITS.allowsRequests(expectedRequests)).isFalse();
	}

}
