package io.archsim.benchmarks.values;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Python's statistics, against values CPython 3.12 computed:
 * {@code statistics.NormalDist().inv_cdf} and {@code math.comb}.
 */
class PythonStatisticsTests {

	// CPython 3.12.13's values: AS241's central branch, then each tail's intermediate
	// branch (r <= 5) and far branch (r > 5), the upper tail through 1 - p
	@ParameterizedTest(name = "{0}: {1}")
	@CsvSource({ "0.9, 1.2815515655446008", "0.975, 1.9599639845400536", "0.001, -3.090232306167813",
			"1e-10, -6.361340902404056", "1e-12, -7.034483825301132", "0.999999999999, 7.0344869100478356" })
	void normalInverse_aProbability_isPythonsQuantile(double probability, double quantile) {
		assertThat(PythonStatistics.normalInverse(probability)).isEqualTo(quantile);
	}

	@ParameterizedTest
	@ValueSource(doubles = { 0.0, 1.0, -0.5, Double.NaN })
	void normalInverse_outsideZeroToOne_isRejected(double probability) {
		assertThatIllegalArgumentException().isThrownBy(() -> PythonStatistics.normalInverse(probability))
			.withMessage("p must be in the range 0.0 < p < 1.0");
	}

	@Test
	void comb_largeCounts_isExact() {
		BigInteger ways = PythonStatistics.comb(67, 33);

		assertThat(ways).isEqualTo(new BigInteger("14226520737620288370"));
	}

}
