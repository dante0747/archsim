package io.archsim.engine.numeric;

import java.util.stream.DoubleStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Python 3.12's built-in {@code sum()} of floats: the expected sums are Python's. */
class FloatSumTests {

	@Test
	void of_tenTenths_isExactlyOne() {
		DoubleStream tenths = DoubleStream.generate(() -> 0.1).limit(10);

		double sum = FloatSum.of(tenths);

		assertThat(sum).isEqualTo(1.0);
	}

	@Test
	void of_cancellingTerms_keepsTheSmallOne() {
		double sum = FloatSum.of(DoubleStream.of(1e100, 1.0, -1e100));

		assertThat(sum).isEqualTo(1.0);
	}

	@Test
	void of_aTieBetweenTwoFloats_isNotAlwaysCorrectlyRounded() {
		double sum = FloatSum.of(DoubleStream.of(1e16, 1.0, 1e-16));

		assertThat(sum).isEqualTo(1e16);
	}

	@Test
	void of_anInfinity_isInfinite() {
		double sum = FloatSum.of(DoubleStream.of(1.0, Double.POSITIVE_INFINITY));

		assertThat(sum).isEqualTo(Double.POSITIVE_INFINITY);
	}

	@Test
	void of_negativeZeros_isZeroAsTheSumStartsFromZero() {
		double sum = FloatSum.of(DoubleStream.of(-0.0, -0.0));

		assertThat(Math.copySign(1.0, sum)).isPositive();
	}

}
