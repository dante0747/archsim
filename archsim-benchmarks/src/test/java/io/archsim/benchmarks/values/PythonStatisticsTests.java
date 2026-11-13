package io.archsim.benchmarks.values;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Python's statistics, against values CPython 3.12 computed:
 * {@code statistics.NormalDist().inv_cdf}, {@code math.comb}, {@code math.fsum} and
 * {@code statistics.fmean}.
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

	// CPython 3.12.13's math.fsum: correctly rounded, where 3.12's sum gives 1.0 for the
	// first too but not for the others
	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|',
			value = { "0.1 0.1 0.1 0.1 0.1 0.1 0.1 0.1 0.1 0.1 | 1.0",
					"1e100 1.0 -1e100 1e-100 1e50 -1.0 -1e50 | 1e-100", "1e-16 1.0 1e16 | 1.0000000000000002e16",
					"9007199254740992.0 -0.5 -5.551115123125783e-17 | 9007199254740991.0" })
	void fsum_floats_isTheirExactSumRoundedOnce(String values, double sum) {
		assertThat(PythonStatistics.fsum(doubles(values))).isEqualTo(sum);
	}

	@Test
	void fsum_noValues_isZero() {
		assertThat(PythonStatistics.fsum(List.of())).isZero();
	}

	@Test
	void fsum_anInfinity_isThatInfinity() {
		assertThat(PythonStatistics.fsum(List.of(Double.POSITIVE_INFINITY, 1.0))).isEqualTo(Double.POSITIVE_INFINITY);
	}

	@Test
	void fsum_aNan_isNan() {
		assertThat(PythonStatistics.fsum(List.of(Double.NaN, 1.0))).isNaN();
	}

	@Test
	void fsum_bothInfinities_isRejected() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> PythonStatistics.fsum(List.of(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)))
			.withMessage("-inf + inf in fsum");
	}

	@Test
	void fsum_finiteValuesWhoseSumOverflows_isRejected() {
		assertThatExceptionOfType(ArithmeticException.class)
			.isThrownBy(() -> PythonStatistics.fsum(List.of(1e308, 1e308)))
			.withMessage("intermediate overflow in fsum");
	}

	// CPython 3.12.13's statistics.fmean: fsum over the count
	@Test
	void fmean_floats_isTheirFsumOverTheirCount() {
		assertThat(PythonStatistics.fmean(doubles("0.1 0.2 0.3 0.4 0.5 0.6 0.7"))).isEqualTo(0.39999999999999997);
	}

	@Test
	void fmean_noValues_isRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> PythonStatistics.fmean(List.of()))
			.withMessage("fmean requires at least one data point");
	}

	private static List<Double> doubles(String values) {
		return Pattern.compile("\\S+").matcher(values).results().map((match) -> Double.valueOf(match.group())).toList();
	}

}
