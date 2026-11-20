package io.archsim.benchmarks.range;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The model's own error by maximum likelihood (ADR-0094): what the calibration spread
 * leaves of the runs' log errors, as an error each experiment shares between its runs and
 * one each run has of its own.
 */
class ModelErrorTests {

	@Test
	void of_runsThatShareTheirExperimentsError_isAnErrorBetweenExperiments() {
		ModelError.Fit fit = ModelError.of(List.of(0.3, 0.3, 0.3, -0.3, -0.3, -0.3),
				List.of(0.05, 0.05, 0.05, 0.05, 0.05, 0.05), List.of("a", "a", "a", "b", "b", "b"));

		// the maximum likelihood: the experiments' mean error squared, less its variance
		assertThat(fit.between()).isCloseTo(Math.sqrt(0.3 * 0.3 - 0.05 * 0.05 / 3), within(0.0005));
		assertThat(fit.within()).isZero();
	}

	@Test
	void of_runsThatDifferWithinTheirExperiment_isAnErrorWithin() {
		ModelError.Fit fit = ModelError.of(List.of(0.2, -0.2, 0.2, -0.2), List.of(0.0, 0.0, 0.0, 0.0),
				List.of("a", "a", "b", "b"));

		assertThat(fit.within()).isCloseTo(0.2, within(0.001));
		assertThat(fit.between()).isZero();
	}

	// one run per experiment cannot tell the two apart: together they are the root mean
	// square error
	@Test
	void of_oneRunPerExperiment_isTheirRootMeanSquareErrorTogether() {
		ModelError.Fit fit = ModelError.of(List.of(0.2, -0.2, 0.2), List.of(0.0, 0.0, 0.0), List.of("a", "b", "c"));

		assertThat(fit.sigma()).isCloseTo(0.2, within(0.001));
	}

	@Test
	void of_errorsTheSpreadExplains_leaveTheModelNone() {
		ModelError.Fit fit = ModelError.of(List.of(0.05, -0.05), List.of(0.3, 0.3), List.of("a", "b"));

		assertThat(fit.sigma()).isZero();
	}

	@Test
	void of_errorsBeyondTheSpread_areTheModelsInQuadrature() {
		ModelError.Fit fit = ModelError.of(List.of(0.3, -0.3), List.of(0.1, 0.1), List.of("a", "b"));

		assertThat(fit.sigma()).isCloseTo(Math.sqrt(0.3 * 0.3 - 0.1 * 0.1), within(0.001));
	}

	@Test
	void of_noRuns_isZero() {
		assertThat(ModelError.of(List.of(), List.of(), List.of())).isEqualTo(new ModelError.Fit(0.0, 0.0));
	}

	@Test
	void sigma_aFit_isItsTwoErrorsInQuadrature() {
		assertThat(new ModelError.Fit(0.3, 0.4).sigma()).isCloseTo(0.5, within(1e-12));
	}

}
