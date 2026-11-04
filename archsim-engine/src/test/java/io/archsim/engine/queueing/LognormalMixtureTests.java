package io.archsim.engine.queueing;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The p95 of a mix of times; each case is what the Python engine 0.16.0 gives. */
class LognormalMixtureTests {

	@Test
	void p95_noTimes_isUnknown() {
		assertThat(LognormalMixture.p95(List.of())).isEmpty();
	}

	@Test
	void p95_onlyTimesOfNoWeight_isUnknown() {
		assertThat(LognormalMixture.p95(List.of(new WeightedTime(0, new Moments(5, 1))))).isEmpty();
	}

	@Test
	void p95_aTimeThatIsAlwaysZero_isZero() {
		assertThat(LognormalMixture.p95(List.of(new WeightedTime(1, Moments.ZERO)))).hasValue(0.0);
	}

	@Test
	void p95_aConstantTime_isTheConstant() {
		assertThat(LognormalMixture.p95(List.of(new WeightedTime(1, new Moments(10, 0)))).getAsDouble()).isCloseTo(10.0,
				within(1e-12));
	}

	@Test
	void p95_aConstantMixedWithZero_isTheConstant() {
		assertThat(LognormalMixture
			.p95(List.of(new WeightedTime(1, new Moments(10, 0)), new WeightedTime(1, Moments.ZERO)))
			.getAsDouble()).isCloseTo(10.0, within(1e-12));
	}

	@Test
	void p95_twoVariableTimes_isTheMixturesPercentile() {
		assertThat(LognormalMixture
			.p95(List.of(new WeightedTime(3, new Moments(10, 25)), new WeightedTime(1, new Moments(40, 400))))
			.getAsDouble()).isCloseTo(53.26475929554858, within(1e-12));
	}

}
