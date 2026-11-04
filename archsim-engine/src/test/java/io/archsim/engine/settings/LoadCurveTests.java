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

}
