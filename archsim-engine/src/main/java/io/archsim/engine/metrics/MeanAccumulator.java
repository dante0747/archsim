package io.archsim.engine.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;

/**
 * A running mean per time bucket and over the measurement window.
 */
public final class MeanAccumulator {

	private final Timeline timeline;

	private final double[] sums;

	private final long[] counts;

	private double windowSum;

	private long windowCount;

	/**
	 * An empty mean.
	 * @param timeline the run's timeline
	 */
	public MeanAccumulator(Timeline timeline) {
		this.timeline = timeline;
		this.sums = new double[timeline.buckets()];
		this.counts = new long[timeline.buckets()];
	}

	/**
	 * Records a value.
	 * @param time when
	 * @param value the value
	 */
	public void record(double time, double value) {
		int bucket = this.timeline.index(time);
		this.sums[bucket] += value;
		this.counts[bucket]++;
		if (this.timeline.inWindow(time)) {
			this.windowSum += value;
			this.windowCount++;
		}
	}

	/**
	 * The mean in each bucket, rounded to 4 decimals.
	 * @return one mean per bucket, {@code null} for a bucket with no value
	 */
	public List<@Nullable Double> series() {
		List<@Nullable Double> series = new ArrayList<>();
		for (int bucket = 0; bucket < this.sums.length; bucket++) {
			long count = this.counts[bucket];
			series.add((count != 0) ? Rounding.of(this.sums[bucket] / count) : null);
		}
		return series;
	}

	/**
	 * The mean over the measurement window.
	 * @return the mean, empty when no value fell in the window
	 */
	public OptionalDouble windowMean() {
		return (this.windowCount != 0) ? OptionalDouble.of(this.windowSum / this.windowCount) : OptionalDouble.empty();
	}

}
