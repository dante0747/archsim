package io.archsim.engine.queueing;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.archsim.model.distribution.Constant;
import io.archsim.model.distribution.Empirical;
import io.archsim.model.distribution.Exponential;
import io.archsim.model.distribution.LogNormal;
import io.archsim.model.distribution.Quantile;
import io.archsim.model.distribution.Uniform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The moments of a time; each case is what the Python engine 0.16.0 gives. */
class MomentsTests {

	@Test
	void of_aConstant_hasNoVariance() {
		assertThat(Moments.of(new Constant(7))).isEqualTo(new Moments(7.0, 0.0));
	}

	@Test
	void scaledBy_aFactor_scalesTheMeanAndTheVarianceByItsSquare() {
		assertThat(new Moments(10, 4).scaledBy(1.5)).isEqualTo(new Moments(15.0, 9.0));
	}

	@Test
	void of_anExponential_hasItsMeanSquaredAsItsVariance() {
		assertThat(Moments.of(new Exponential(900))).isEqualTo(new Moments(900.0, 810_000.0));
	}

	@Test
	void of_aUniform_hasItsWidthSquaredOverTwelveAsItsVariance() {
		assertThat(Moments.of(new Uniform(1, 3))).isEqualTo(new Moments(2.0, 0.3333333333333333));
	}

	@Test
	void of_anEmpirical_isLinearBetweenItsQuantiles() {
		Moments moments = Moments.of(new Empirical(
				List.of(new Quantile(0, 1), new Quantile(0.5, 2), new Quantile(0.95, 6), new Quantile(1, 9))));

		assertThat(moments.mean()).isCloseTo(2.9250000000000003, within(1e-15));
		assertThat(moments.variance()).isCloseTo(3.261041666666669, within(1e-14));
	}

	@Test
	void of_aLogNormal_hasItsMeanAndVariance() {
		Moments moments = Moments.of(new LogNormal(8, 25));

		assertThat(moments.mean()).isCloseTo(10.169334708591919, within(1e-13));
		assertThat(moments.variance()).isCloseTo(63.68991946723994, within(1e-12));
	}

	@Test
	void of_noDistribution_isZero() {
		assertThat(Moments.of(null)).isEqualTo(Moments.ZERO);
	}

	@Test
	void times_twoAndAHalf_takesTwoCallsAndAHalfChanceOfAThird() {
		assertThat(new Moments(10, 4).times(2.5)).isEqualTo(new Moments(25.0, 35.0));
	}

	@Test
	void times_aFraction_isTheCallWithThatChance() {
		Moments moments = new Moments(10, 4).times(0.3);

		assertThat(moments.mean()).isCloseTo(3.0, within(1e-15));
		assertThat(moments.variance()).isCloseTo(22.2, within(1e-13));
	}

	@Test
	void times_none_isZero() {
		assertThat(new Moments(10, 4).times(0)).isEqualTo(Moments.ZERO);
	}

	@Test
	void plus_twoTimes_addsTheirMeansAndVariances() {
		assertThat(new Moments(10, 4).plus(new Moments(5, 1))).isEqualTo(new Moments(15, 5));
	}

}
