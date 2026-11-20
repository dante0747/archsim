package io.archsim.benchmarks.inputs;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.archsim.engine.calibration.resources.RatePoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The law fitted to CPU per request measured at a few rates.
 */
class CostLawsTests {

	private static final CostLaw LAW = new CostLaw(0.07, 6.0, 0.5);

	@Test
	void fit_pointsOnALaw_findsThatLaw() {
		List<RatePoint> points = List.of(at(50), at(650), at(2050), at(3470));

		CostLaw fitted = CostLaws.fit(points).orElseThrow();

		assertThat(fitted.exponent()).isEqualTo(0.5);
		assertThat(fitted.floorMs()).isCloseTo(0.07, within(1e-9));
		assertThat(fitted.sharedMs()).isCloseTo(6.0, within(1e-9));
	}

	// cache-aside's latest staircase, CPU per request net of idle; its ladder measured
	// 0.1382 ms at 6,210 req/s with the core 86% busy, 27% under the top rate's cost
	@Test
	void fit_cacheAsidesStaircase_chargesItsCostNearTheKneeWithinSevenPercent() {
		List<RatePoint> staircase = List.of(new RatePoint(50, 0.9139), new RatePoint(651, 0.3047),
				new RatePoint(2052, 0.1917), new RatePoint(3470, 0.1754));

		CostLaw fitted = CostLaws.fit(staircase).orElseThrow();

		assertThat(fitted.msAt(6210.1) / 0.13823668).isCloseTo(1.0, within(0.07));
	}

	@Test
	void fit_aCostThatRisesWithLoad_isEmpty() {
		List<RatePoint> rising = List.of(new RatePoint(50, 1.0), new RatePoint(500, 1.05), new RatePoint(1300, 1.1));

		assertThat(CostLaws.fit(rising)).isEmpty();
	}

	@Test
	void fit_twoRates_isEmpty() {
		assertThat(CostLaws.fit(List.of(at(50), at(3470)))).isEmpty();
	}

	@Test
	void fit_threePointsAtTwoRates_isEmpty() {
		assertThat(CostLaws.fit(List.of(at(50), at(50), at(3470)))).isEmpty();
	}

	@Test
	void fit_ratesThatHardlySpread_isEmpty() {
		assertThat(CostLaws.fit(List.of(at(100), at(110), at(120)))).isEmpty();
	}

	// steeper than any exponent the law allows: the floor would fall below 0 at an
	// exponent of 1, so it is held there and the shared part fitted through the origin on
	// the relative error, 6 x the rates' square roots summed over the rates summed
	@Test
	void fit_aFallSteeperThanAnyExponent_holdsTheFloorAtZero() {
		List<RatePoint> points = List.of(steep(50), steep(650), steep(2050));

		CostLaw fitted = CostLaws.fit(points).orElseThrow();

		assertThat(fitted.floorMs()).isZero();
		assertThat(fitted.exponent()).isEqualTo(1.0);
		assertThat(fitted.sharedMs()).isCloseTo(6 * (Math.sqrt(50) + Math.sqrt(650) + Math.sqrt(2050)) / 2750,
				within(1e-12));
	}

	@Test
	void fit_pointsWithoutARateOrCost_areLeftOut() {
		List<RatePoint> points = List.of(at(50), at(650), at(2050), new RatePoint(0, 0.5), new RatePoint(800, -1),
				new RatePoint(Double.NaN, 0.2));

		assertThat(CostLaws.fit(points).orElseThrow().exponent()).isEqualTo(0.5);
	}

	@Test
	void usable_pointsInAnyOrder_areByRisingRate() {
		assertThat(CostLaws.usable(List.of(at(650), at(50)))).extracting(RatePoint::rate).containsExactly(50.0, 650.0);
	}

	private static RatePoint at(double rate) {
		return new RatePoint(rate, LAW.msAt(rate));
	}

	// 6 × rate^-1.5 ms
	private static RatePoint steep(double rate) {
		return new RatePoint(rate, 6 * Math.pow(rate, -1.5));
	}

}
