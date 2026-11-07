package io.archsim.engine.calibration.resources;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How CPU per request continues above a curve's top rate, as the Python engine's
 * {@code extend_load_curve} carries it, where the golden files do not reach. Expected
 * values are the Python engine's.
 */
class CurveExtensionsTests {

	@Test
	void extend_fallOneMeasuredStretchShortOfFillingTheCpus_isCarriedThatFar() {
		CurveExtension extension = extend(new RatePoint(60, 6.0), new RatePoint(60, 6.02), new RatePoint(80, 5.5),
				new RatePoint(80, 5.52), new RatePoint(100, 5.0), new RatePoint(100, 5.02));

		assertThat(extension.slope()).isEqualTo(-1.9472543695722944);
		assertThat(extension.error()).isEqualTo(0.07493719984977681);
		assertThat(extension.points()).containsExactly(new RatePoint(166.66666666666669, 4.015292572032181));
		assertThat(extension.reason()).isEqualTo("a fall of 1.95 ms per unit of ln(rate) (26.0 standard errors) "
				+ "carried from 100 to 167 req/s (one measured stretch on)");
	}

	@Test
	void extend_noPasses_isEmpty() {
		assertThat(CurveExtensions.extend(List.of(), 1.0, CurveExtensions.Bounds.DEFAULT)).isEmpty();
	}

	@Test
	void extend_topThatFilledTheCpus_isFlat() {
		assertThat(extend(new RatePoint(100, 12.0)).reason()).isEqualTo("the top rate filled the CPUs");
	}

	@Test
	void extend_oneRateBusyEnough_isFlat() {
		CurveExtension extension = extend(new RatePoint(100, 5.0), new RatePoint(100, 5.2));

		assertThat(extension.reason()).isEqualTo("one rate kept the CPUs at least 30% busy");
		assertThat(extension.points()).isEmpty();
	}

	@Test
	void extend_fallWithoutScatter_isFlatWithItsSlope() {
		CurveExtension extension = extend(new RatePoint(100, 5.0), new RatePoint(90, 5.2));

		assertThat(extension.reason()).isEqualTo("no fall above the noise (no scatter to tell it from)");
		assertThat(extension.slope()).isEqualTo(-1.8982443162059828);
		assertThat(extension.error()).isNull();
	}

	@Test
	void extend_fallWithinTheNoise_isFlat() {
		CurveExtension extension = extend(new RatePoint(80, 5.0), new RatePoint(80, 6.5), new RatePoint(100, 5.0),
				new RatePoint(100, 6.4), new RatePoint(90, 5.9));

		assertThat(extension.reason()).isEqualTo("no fall above the noise (2 standard errors)");
		assertThat(extension.error()).isEqualTo(3.774831920761238);
	}

	@Test
	void extend_fallSoSteepTheBusyTimeWouldShrink_isFlat() {
		CurveExtension extension = extend(new RatePoint(60, 9.0), new RatePoint(60, 9.02), new RatePoint(100, 5.0),
				new RatePoint(100, 5.02));

		assertThat(extension.reason()).isEqualTo("a fall so steep the CPUs' busy time would shrink");
		assertThat(extension.factors()).isEmpty();
	}

	private static CurveExtension extend(RatePoint... passes) {
		return CurveExtensions.extend(List.of(passes), 1.0, CurveExtensions.Bounds.DEFAULT).orElseThrow();
	}

}
