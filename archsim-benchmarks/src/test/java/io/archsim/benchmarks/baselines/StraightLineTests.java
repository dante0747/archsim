package io.archsim.benchmarks.baselines;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A straight line fitted to points by least squares.
 */
class StraightLineTests {

	private static final double EXACT = 1e-12;

	@Test
	void through_pointsOnALine_isThatLine() {
		List<StraightLine.Point> points = List.of(point(1, 3), point(2, 5), point(3, 7));

		StraightLine line = StraightLine.through(points).orElseThrow();

		assertThat(line.intercept()).isCloseTo(1.0, within(EXACT));
		assertThat(line.slope()).isCloseTo(2.0, within(EXACT));
		assertThat(line.rSquared()).isCloseTo(1.0, within(EXACT));
	}

	// the least squares line through (0, 0), (1, 1) and (2, 1): y = 1/6 + x/2
	@Test
	void through_pointsOffALine_hasTheLeastSquaredError() {
		List<StraightLine.Point> points = List.of(point(0, 0), point(1, 1), point(2, 1));

		StraightLine line = StraightLine.through(points).orElseThrow();

		assertThat(line.intercept()).isCloseTo(1.0 / 6, within(EXACT));
		assertThat(line.slope()).isCloseTo(0.5, within(EXACT));
		assertThat(line.rSquared()).isCloseTo(0.75, within(EXACT));
	}

	@Test
	void through_pointsAtOnePlace_isNoLine() {
		List<StraightLine.Point> points = List.of(point(2, 1), point(2, 3));

		Optional<StraightLine> line = StraightLine.through(points);

		assertThat(line).isEmpty();
	}

	@Test
	void reaches_aValueAboveThePoints_isWhereTheLineGetsThere() {
		StraightLine line = new StraightLine(1.0, 2.0, 1.0);

		double reached = line.reaches(11.0);

		assertThat(reached).isCloseTo(5.0, within(EXACT));
	}

	private static StraightLine.Point point(double at, double value) {
		return new StraightLine.Point(at, value);
	}

}
