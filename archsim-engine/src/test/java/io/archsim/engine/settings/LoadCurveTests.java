package io.archsim.engine.settings;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.component.service.LoadPoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** A service's time per request as its load changes. */
class LoadCurveTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final LoadCurve CURVE = LoadCurve
		.ofJson(JSON.readTree("[{\"rps\": 400, \"factor\": 0.5}, {\"rps\": 100, \"factor\": 1.0}]"));

	/** A service 1.8 times slower at 50 req/s than at 1,674 req/s. */
	private static final LoadCurve SLOWING = LoadCurve
		.ofJson(JSON.readTree("[{\"rps\": 50, \"factor\": 1.8}, {\"rps\": 1674, \"factor\": 1.0}]"));

	@Test
	void factorAt_noPoints_isOne() {
		assertThat(LoadCurve.FLAT.factorAt(250)).isEqualTo(1.0);
	}

	@Test
	void factorAt_belowTheFirstPoint_isItsFactor() {
		assertThat(CURVE.factorAt(10)).isEqualTo(1.0);
	}

	@Test
	void factorAt_aboveTheLastPoint_isItsFactor() {
		assertThat(CURVE.factorAt(1000)).isEqualTo(0.5);
	}

	@Test
	void factorAt_betweenPoints_isInterpolatedInTheLogOfTheRate() {
		assertThat(CURVE.factorAt(200)).isCloseTo(0.75, within(1e-12));
	}

	@Test
	void of_aConfigsPoints_isTheSameCurve() {
		LoadCurve curve = LoadCurve.of(List.of(new LoadPoint(400, 0.5), new LoadPoint(100, 1.0)));

		assertThat(curve.factorAt(200)).isEqualTo(CURVE.factorAt(200));
	}

	@Test
	void ofJson_pointsWithoutAPositiveRateAndFactor_areSkipped() {
		LoadCurve curve = LoadCurve
			.ofJson(JSON.readTree("[{\"rps\": 0, \"factor\": 2}, {\"rps\": 50, \"factor\": true}]"));

		assertThat(curve.isEmpty()).isTrue();
	}

	@Test
	void capacityRps_aRateOnTheCurve_isWhereTheReplicasCpuFillsAtThatRatesCost() {
		// the Python engine's value
		assertThat(SLOWING.capacityRps(100)).isEqualTo(56.418049988530264);
	}

	@Test
	void capacityRps_aRateAboveTheLastPoint_isItsCapacityAtTheLastFactor() {
		assertThat(SLOWING.capacityRps(2000)).isEqualTo(2000.0);
	}

	@Test
	void capacityRps_aRateBelowTheFirstPoint_isItsCapacityAtTheFirstFactor() {
		assertThat(SLOWING.capacityRps(20)).isEqualTo(11.11111111111111);
	}

	@Test
	void capacityRps_noPoints_isTheRateAtFactorOne() {
		assertThat(LoadCurve.FLAT.capacityRps(123.5)).isEqualTo(123.5);
	}

	@Test
	void capacityRps_noRate_isZero() {
		assertThat(CURVE.capacityRps(0)).isZero();
		assertThat(LoadCurve.FLAT.capacityRps(-3)).isZero();
	}

	@Test
	void rateFilling_noPoints_isTheBudgetOverTheTimeARequestTakes() {
		double rate = LoadCurve.FLAT.rateFilling(10.0, 1000.0, 5.0);

		assertThat(rate).isEqualTo(1000.0 / 15.0);
	}

	@Test
	void rateFilling_aRequestThatTakesNoTime_isInfinite() {
		double rate = SLOWING.rateFilling(0.0, 1000.0, 0.0);

		assertThat(rate).isInfinite();
	}

	@Test
	void rateFilling_workThatFollowsTheCurveAndTimeThatDoesNot_isWherePythonFindsIt() {
		LoadCurve doubling = LoadCurve
			.ofJson(JSON.readTree("[{\"rps\": 20, \"factor\": 1}, {\"rps\": 200, \"factor\": 2}]"));

		double rate = doubling.rateFilling(30.0, 20_000.0, 15.0);

		assertThat(rate).isCloseTo(266.66666666666674, within(1e-9));
	}

	@Test
	void describe_noPoints_saysSo() {
		assertThat(LoadCurve.FLAT.describe()).isEqualTo("no load curve");
	}

	@Test
	void describe_onePoint_isItsFactorAtEveryRate() {
		LoadCurve flat = LoadCurve.ofJson(JSON.readTree("[{\"rps\": 50, \"factor\": 1.5}]"));

		assertThat(flat.describe()).isEqualTo("×1.5 at every rate");
	}

	@Test
	void describe_points_runsFromTheFirstToTheLast() {
		LoadCurve curve = LoadCurve.ofJson(JSON.readTree("[{\"rps\": 50, \"factor\": 1.8}, "
				+ "{\"rps\": 1674.4, \"factor\": 1.0}, {\"rps\": 300, \"factor\": 1.2}]"));

		assertThat(curve.describe()).isEqualTo("×1.8 at 50 req/s to ×1 at 1,674 req/s (3 points)");
	}

}
