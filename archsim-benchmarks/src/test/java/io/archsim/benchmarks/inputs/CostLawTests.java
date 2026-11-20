package io.archsim.benchmarks.inputs;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.archsim.engine.calibration.resources.RatePoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

/**
 * A service's CPU per request as a law of the rate one replica gets.
 */
class CostLawTests {

	// fills one CPU at about 7,076 req/s
	private static final CostLaw CACHE_ASIDE = new CostLaw(0.07, 6.0, 0.5);

	@Test
	void msAt_aRate_isTheFloorAndTheSharedPartAtThatRate() {
		assertThat(CACHE_ASIDE.msAt(3600)).isCloseTo(0.07 + 6.0 / 60, within(1e-12));
	}

	@Test
	void fillRps_ofOneCore_isWhereTheRateTimesTheCostIsASecondOfCpuEachSecond() {
		double fill = CACHE_ASIDE.fillRps(1).orElseThrow();

		assertThat(fill * CACHE_ASIDE.msAt(fill)).isCloseTo(1000.0, within(1e-6));
	}

	@Test
	void fillRps_ofTwoCores_isMoreThanTwiceAsFar() {
		assertThat(CACHE_ASIDE.fillRps(2).orElseThrow()).isGreaterThan(2 * CACHE_ASIDE.fillRps(1).orElseThrow());
	}

	// 5,000 ms at one request a second: the CPU fills at 0.04
	@Test
	void fillRps_whereTheCpusFillBelowOneRequestASecond_isWhereTheyFill() {
		assertThat(new CostLaw(0.0, 5000.0, 0.5).fillRps(1).orElseThrow()).isCloseTo(0.04, within(1e-12));
	}

	@Test
	void fillRps_ofNoCores_isEmpty() {
		assertThat(CACHE_ASIDE.fillRps(0)).isEmpty();
	}

	@Test
	void fillRps_withNoFloorAndAnExponentOfOne_isEmptySinceTheBusyTimeNeverGrows() {
		assertThat(new CostLaw(0.0, 50.0, 1.0).fillRps(1)).isEmpty();
	}

	@Test
	void fillRps_aBusyTimeThatBarelyGrows_isEmpty() {
		assertThat(new CostLaw(0.0, 0.001, 0.99).fillRps(64)).isEmpty();
	}

	@Test
	void endAbove_whereTheCpusFillBeforeTwiceTheTopRate_isWhereTheyFill() {
		assertThat(CACHE_ASIDE.endAbove(3600, 1))
			.isEqualTo(new CostLaw.End(CACHE_ASIDE.fillRps(1).orElseThrow(), CostLaw.Reach.FILL));
	}

	@Test
	void endAbove_whereTheCpusFillFurtherOn_isTwiceTheTopRate() {
		assertThat(CACHE_ASIDE.endAbove(1000, 1)).isEqualTo(new CostLaw.End(2000.0, CostLaw.Reach.FURTHEST));
	}

	@Test
	void endAbove_whereTheTopRateHadFilledTheCpus_isTheTopRate() {
		assertThat(CACHE_ASIDE.endAbove(8000, 1)).isEqualTo(new CostLaw.End(8000.0, CostLaw.Reach.TOP));
	}

	@Test
	void endAbove_whereTheCpusNeverFill_isTwiceTheTopRate() {
		assertThat(new CostLaw(0.0, 50.0, 1.0).endAbove(1000, 1))
			.isEqualTo(new CostLaw.End(2000.0, CostLaw.Reach.FURTHEST));
	}

	// a quarter apart from 200: 102.4 and 390.625 are within half a step of the ends
	@Test
	void factors_fromALowRateToAHighOne_stepByAQuarterFromTheReference() {
		assertThat(CACHE_ASIDE.factors(100, 200, 400)).extracting(RatePoint::rate)
			.containsExactly(100.0, 128.0, 160.0, 200.0, 250.0, 312.5, 400.0);
	}

	// cache-aside's latest staircase: 3,470 / 1.25^19 is 50.008
	@Test
	void factors_aGridRateJustAboveTheFirst_isLeftOut() {
		List<RatePoint> factors = CACHE_ASIDE.factors(50, 3470, 6940);

		assertThat(factors.get(1).rate() / factors.getFirst().rate()).isGreaterThan(Math.sqrt(1.25));
	}

	@Test
	void factors_eachRate_isItsCostOverTheReferences() {
		List<RatePoint> factors = CACHE_ASIDE.factors(100, 200, 400);

		assertThat(factors.getFirst().value()).isCloseTo(CACHE_ASIDE.msAt(100) / CACHE_ASIDE.msAt(200), within(1e-12));
		assertThat(factors.getLast().value()).isCloseTo(CACHE_ASIDE.msAt(400) / CACHE_ASIDE.msAt(200), within(1e-12));
	}

	@Test
	void factors_theReferenceRate_isAtOne() {
		assertThat(CACHE_ASIDE.factors(100, 200, 400)).filteredOn((factor) -> factor.rate() == 200.0)
			.singleElement()
			.extracting(RatePoint::value)
			.isEqualTo(1.0);
	}

	@Test
	void factors_endingAtTheReference_endThere() {
		assertThat(CACHE_ASIDE.factors(100, 200, 200)).extracting(RatePoint::rate)
			.containsExactly(100.0, 128.0, 160.0, 200.0);
	}

	@Test
	void factors_startingAtTheReference_startThere() {
		assertThat(CACHE_ASIDE.factors(200, 200, 400)).extracting(RatePoint::rate)
			.containsExactly(200.0, 250.0, 312.5, 400.0);
	}

	@Test
	void factors_oneRate_isThatRateAtOne() {
		assertThat(CACHE_ASIDE.factors(200, 200, 200)).containsExactly(new RatePoint(200, 1.0));
	}

	@Test
	void factors_aReferenceAboveTheLastRate_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> CACHE_ASIDE.factors(100, 500, 400));
	}

	@Test
	void describe_aLaw_namesItsFloorSharedPartAndExponent() {
		assertThat(CACHE_ASIDE.describe()).isEqualTo("0.07 + 6 × rate^-0.5 ms");
	}

	@Test
	void constructor_aNegativeFloor_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> new CostLaw(-0.01, 6.0, 0.5));
	}

	@Test
	void constructor_anEndlessFloor_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> new CostLaw(Double.POSITIVE_INFINITY, 6.0, 0.5));
	}

	@ParameterizedTest
	@ValueSource(doubles = { 0.0, Double.NaN, Double.POSITIVE_INFINITY })
	void constructor_noSharedPart_isRefused(double sharedMs) {
		assertThatIllegalArgumentException().isThrownBy(() -> new CostLaw(0.07, sharedMs, 0.5));
	}

	@ParameterizedTest
	@ValueSource(doubles = { 0.0, 1.01, Double.NaN })
	void constructor_anExponentOutsideZeroToOne_isRefused(double exponent) {
		assertThatIllegalArgumentException().isThrownBy(() -> new CostLaw(0.07, 6.0, exponent));
	}

}
