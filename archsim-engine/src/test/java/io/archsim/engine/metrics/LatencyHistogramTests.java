package io.archsim.engine.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The latency histogram beyond the frozen engine's recorded cases
 * ({@link HistogramCasesTests}).
 */
class LatencyHistogramTests {

	@Test
	void record_notANumber_isRefused() {
		LatencyHistogram histogram = new LatencyHistogram();

		assertThatIllegalArgumentException().isThrownBy(() -> histogram.record(Double.NaN))
			.withMessage("a latency is a finite number of milliseconds; got NaN");
	}

	@Test
	void record_infinity_isRefused() {
		LatencyHistogram histogram = new LatencyHistogram();

		assertThatIllegalArgumentException().isThrownBy(() -> histogram.record(Double.POSITIVE_INFINITY))
			.withMessage("a latency is a finite number of milliseconds; got Infinity");
	}

}
