package io.archsim.engine.metrics;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One latency histogram per time bucket, for percentiles over time and over the
 * measurement window.
 */
public final class BucketedHistograms {

	private final Timeline timeline;

	private final @Nullable LatencyHistogram[] histograms;

	/**
	 * Empty histograms.
	 * @param timeline the run's timeline
	 */
	public BucketedHistograms(Timeline timeline) {
		this.timeline = timeline;
		this.histograms = new LatencyHistogram[timeline.buckets()];
	}

	/**
	 * Records a latency in the bucket of its time.
	 * @param time when it ended
	 * @param latency milliseconds
	 */
	public void record(double time, double latency) {
		int bucket = this.timeline.index(time);
		LatencyHistogram histogram = this.histograms[bucket];
		if (histogram == null) {
			histogram = new LatencyHistogram();
			this.histograms[bucket] = histogram;
		}
		histogram.record(latency);
	}

	/**
	 * A percentile in each bucket, rounded to 4 decimals.
	 * @param percentile from 0 to 100
	 * @return one value per bucket, {@code null} for a bucket with no latency
	 */
	public List<@Nullable Double> percentileSeries(double percentile) {
		List<@Nullable Double> series = new ArrayList<>();
		for (LatencyHistogram histogram : this.histograms) {
			series.add((histogram != null) ? Rounding.of(histogram.percentile(percentile)) : null);
		}
		return series;
	}

	/**
	 * The latencies of the buckets inside the measurement window, merged: from the first
	 * bucket that starts in the window, up to the last that ends in it (at least one).
	 * @return the merged histogram
	 */
	public LatencyHistogram window() {
		LatencyHistogram merged = new LatencyHistogram();
		int last = Math.min(this.timeline.windowBucketsEnd(), this.histograms.length);
		for (int bucket = this.timeline.firstWindowBucket(); bucket < last; bucket++) {
			LatencyHistogram histogram = this.histograms[bucket];
			if (histogram != null) {
				merged.merge(histogram);
			}
		}
		return merged;
	}

}
