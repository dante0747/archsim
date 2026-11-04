package io.archsim.engine.numeric;

import java.util.stream.DoubleStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Python's {@code statistics.median}. */
class MedianTests {

	@Test
	void of_anOddCount_isTheMiddleValue() {
		double median = Median.of(DoubleStream.of(7.0, 1.25, 3.0));

		assertThat(median).isEqualTo(3.0);
	}

	@Test
	void of_anEvenCount_isTheMeanOfTheTwoMiddleValues() {
		double median = Median.of(DoubleStream.of(100.5, 99.5));

		assertThat(median).isEqualTo(100.0);
	}

	@Test
	void of_noValues_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> Median.of(DoubleStream.empty()));
	}

}
