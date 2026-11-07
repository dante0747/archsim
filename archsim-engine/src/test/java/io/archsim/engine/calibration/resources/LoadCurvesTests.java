package io.archsim.engine.calibration.resources;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CPU per request against load, fitted as the Python engine's {@code fit_load_curve} fits
 * it, where the golden files do not reach. Expected values are the Python engine's.
 */
class LoadCurvesTests {

	@Test
	void fit_noReference_isRelativeToTheCpuPerRequestOverTheRange() {
		LoadCurve curve = LoadCurves
			.fit(List.of(new RatePoint(50, 0.05), new RatePoint(100, 0.08), new RatePoint(200, 0.2),
					new RatePoint(400, 0.3)), null, 4)
			.orElseThrow();

		assertThat(curve.referenceMs()).isEqualTo(0.84);
		assertThat(curve.points()).containsExactly(new RatePoint(50, 1.1904761904761905),
				new RatePoint(100, 1.1111111111111112), new RatePoint(200, 1.1111111111111112),
				new RatePoint(400, 0.8928571428571429));
		assertThat(curve.measured()).containsExactly(new RatePoint(50, 1.0), new RatePoint(100, 0.8),
				new RatePoint(200, 1.0), new RatePoint(400, 0.75));
		assertThat(curve.error()).isEqualTo(0.1667);
	}

	@Test
	void fit_ratesThatHardlySpread_isEmpty() {
		assertThat(
				LoadCurves.fit(List.of(new RatePoint(100, 0.05), new RatePoint(110, 0.055)), null, LoadCurves.MAX_BINS))
			.isEmpty();
	}

	@Test
	void fit_onePoint_isEmpty() {
		assertThat(LoadCurves.fit(List.of(new RatePoint(100, 0.05)), null, LoadCurves.MAX_BINS)).isEmpty();
	}

	@Test
	void neverRising_laterValueHigher_poolsTheRunIntoItsWeightedMean() {
		assertThat(LoadCurves.neverRising(List.of(3.0, 1.0, 2.0, 0.5), List.of(1.0, 1.0, 3.0, 1.0)))
			.containsExactly(3.0, 1.75, 1.75, 0.5);
	}

}
