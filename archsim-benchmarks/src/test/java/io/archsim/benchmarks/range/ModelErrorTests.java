package io.archsim.benchmarks.range;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The model's own error by maximum likelihood: what the calibration spread leaves of the
 * runs' log errors.
 */
class ModelErrorTests {

	// no calibration spread: the root mean square of the log errors
	@Test
	void sigma_runsWithoutCalibrationSpread_isTheirRootMeanSquareError() {
		assertThat(ModelError.sigma(List.of(0.2, -0.2, 0.2), List.of(0.0, 0.0, 0.0))).isCloseTo(0.2, within(0.001));
	}

	@Test
	void sigma_errorsTheSpreadExplains_leaveTheModelNone() {
		assertThat(ModelError.sigma(List.of(0.05, -0.05), List.of(0.3, 0.3))).isZero();
	}

	@Test
	void sigma_errorsBeyondTheSpread_areTheModelsInQuadrature() {
		assertThat(ModelError.sigma(List.of(0.3, -0.3), List.of(0.1, 0.1))).isCloseTo(Math.sqrt(0.3 * 0.3 - 0.1 * 0.1),
				within(0.001));
	}

	@Test
	void sigma_noRuns_isZero() {
		assertThat(ModelError.sigma(List.of(), List.of())).isZero();
	}

}
