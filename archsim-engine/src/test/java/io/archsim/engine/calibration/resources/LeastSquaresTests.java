package io.archsim.engine.calibration.resources;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Linear systems solved as the Python engine's {@code _solve} solves them.
 */
class LeastSquaresTests {

	@Test
	void gaussian_system_isSolvedWithPartialPivoting() {
		double[][] matrix = { { 1, 2 }, { 3, 4 } };

		assertThat(LeastSquares.gaussian(matrix, new double[] { 5, 6 })).hasValueSatisfying(
				(solution) -> assertThat(solution).containsExactly(-3.9999999999999987, 4.499999999999999));
	}

	@Test
	void gaussian_singularSystem_isEmpty() {
		double[][] matrix = { { 1, 2 }, { 2, 4 } };

		assertThat(LeastSquares.gaussian(matrix, new double[] { 1, 2 })).isEmpty();
	}

}
