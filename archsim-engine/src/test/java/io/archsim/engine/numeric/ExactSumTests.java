package io.archsim.engine.numeric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.DoubleStream;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Python's {@code math.fsum}: the expected sums are Python 3.12's. */
class ExactSumTests {

	@Test
	void of_tenTenths_isExactlyOne() {
		DoubleStream tenths = DoubleStream.generate(() -> 0.1).limit(10);

		double sum = ExactSum.of(tenths);

		assertThat(sum).isEqualTo(1.0);
	}

	@Test
	void of_cancellingTerms_keepsTheSmallOne() {
		double sum = ExactSum.of(DoubleStream.of(1e100, 1.0, -1e100));

		assertThat(sum).isEqualTo(1.0);
	}

	@Test
	void of_aTieBetweenTwoFloats_roundsToEven() {
		double sum = ExactSum.of(DoubleStream.of(1e16, 1.0, 1e-16));

		assertThat(sum).isEqualTo(10000000000000002.0);
	}

	@Property
	void of_termsInAnyOrder_isTheSame(@ForAll @Size(max = 200) List<@DoubleRange(min = -1e6, max = 1e6) Double> terms,
			@ForAll long seed) {
		List<Double> shuffled = new ArrayList<>(terms);
		Collections.shuffle(shuffled, new Random(seed));

		double sum = ExactSum.of(shuffled.stream().mapToDouble(Double::doubleValue));

		assertThat(sum).isEqualTo(ExactSum.of(terms.stream().mapToDouble(Double::doubleValue)));
	}

	@Test
	void of_nothing_isZero() {
		double sum = ExactSum.of(DoubleStream.empty());

		assertThat(sum).isZero();
	}

	@Test
	void of_negativeZeros_isZero() {
		double sum = ExactSum.of(DoubleStream.of(-0.0, -0.0));

		assertThat(Math.copySign(1.0, sum)).isPositive();
	}

	@Test
	void of_finiteTermsThatOverflow_throws() {
		assertThatExceptionOfType(ArithmeticException.class)
			.isThrownBy(() -> ExactSum.of(DoubleStream.of(1e308, 1e308)))
			.withMessage("intermediate overflow in fsum");
	}

	@Test
	void of_bothInfinities_isRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ExactSum.of(DoubleStream.of(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)))
			.withMessage("-inf + inf in fsum");
	}

	@Test
	void of_notANumber_isNotANumber() {
		double sum = ExactSum.of(DoubleStream.of(Double.NaN, 1.0));

		assertThat(sum).isNaN();
	}

	@Test
	void of_finiteTermsAfterAnInfinity_isTheInfinity() {
		double sum = ExactSum.of(DoubleStream.of(1e308, Double.POSITIVE_INFINITY, 1e308));

		assertThat(sum).isEqualTo(Double.POSITIVE_INFINITY);
	}

	@Test
	void of_anInfinity_isInfinite() {
		double sum = ExactSum.of(DoubleStream.of(1.0, Double.POSITIVE_INFINITY));

		assertThat(sum).isEqualTo(Double.POSITIVE_INFINITY);
	}

}
