package io.archsim.engine.parity;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One metric against the bar's two tests: inside Python's range, and within 2% of its
 * median.
 */
class MetricVerdictTests {

	private static final List<Double> PYTHON = List.of(99.0, 100.0, 101.0);

	@Test
	void of_aJavaMedianAbovePythonsRange_isNotInsideTheRange() {
		assertThat(MetricVerdict.of("p95", PYTHON, List.of(101.5)).insideRange()).isFalse();
	}

	@Test
	void of_aJavaMedianBelowPythonsRange_isNotInsideTheRange() {
		assertThat(MetricVerdict.of("p95", PYTHON, List.of(98.5)).insideRange()).isFalse();
	}

	@Test
	void of_aJavaMedianOnTheRangesEnds_isInsideIt() {
		assertThat(List.of(MetricVerdict.of("p95", PYTHON, List.of(99.0)),
				MetricVerdict.of("p95", PYTHON, List.of(101.0))))
			.allMatch(MetricVerdict::insideRange);
	}

	@Test
	void of_aJavaMedianInsideTheRangeButMoreThanTwoPercentOff_holdsOnlyTheRange() {
		MetricVerdict verdict = MetricVerdict.of("p95", List.of(10.0, 20.0, 30.0), List.of(21.0));

		assertThat(List.of(verdict.insideRange(), verdict.withinTwoPercent(), verdict.holds())).containsExactly(true,
				false, false);
	}

	@Test
	void of_noJavaValue_isUnmeasuredAndMisses() {
		MetricVerdict verdict = MetricVerdict.of("utilization api", PYTHON, List.of());

		assertThat(verdict.javaMedian()).isNull();
		assertThat(verdict.distance()).isNull();
		assertThat(verdict.holds()).isFalse();
	}

}
