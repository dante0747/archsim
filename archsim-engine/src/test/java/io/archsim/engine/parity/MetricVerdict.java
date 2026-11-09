package io.archsim.engine.parity;

import java.util.Collections;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One metric of a template case against the bar's two tests.
 *
 * @param metric what is measured: {@code p50}, {@code p95}, {@code throughput} or
 * {@code utilization <part>}
 * @param pythonLow the lowest of the frozen engine's values over the seeds
 * @param pythonHigh the highest
 * @param pythonMedian their median
 * @param javaMedian the Java simulator's median; {@code null} when no Java run measured
 * it
 * @param insideRange whether the Java median is inside Python's range
 * @param withinTwoPercent whether it is within 2% of Python's median
 * @param distance how far it is from Python's median, as a share of it; {@code null} when
 * no Java run measured it
 */
record MetricVerdict(String metric, double pythonLow, double pythonHigh, double pythonMedian,
		@Nullable Double javaMedian, boolean insideRange, boolean withinTwoPercent, @Nullable Double distance) {

	/**
	 * A metric judged.
	 * @param metric what is measured
	 * @param python the frozen engine's values, at least one
	 * @param java the Java simulator's values
	 * @return the verdict
	 */
	static MetricVerdict of(String metric, List<Double> python, List<Double> java) {
		double low = Collections.min(python);
		double high = Collections.max(python);
		double median = ParityBar.median(python);
		if (java.isEmpty()) {
			return new MetricVerdict(metric, low, high, median, null, false, false, null);
		}
		double javaMedian = ParityBar.median(java);
		return new MetricVerdict(metric, low, high, median, javaMedian, low <= javaMedian && javaMedian <= high,
				ParityBar.withinTwoPercent(median, javaMedian), ParityBar.distance(median, javaMedian));
	}

	/**
	 * Whether the metric meets the bar: both tests.
	 * @return whether it is inside the range and within 2%
	 */
	boolean holds() {
		return this.insideRange && this.withinTwoPercent;
	}

}
