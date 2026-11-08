package io.archsim.engine.metrics;

/**
 * A count of events per time bucket, in all and inside the measurement window.
 */
public final class Counter {

	private static final double MS_PER_SECOND = 1000.0;

	private final Timeline timeline;

	private final long[] buckets;

	private long total;

	private long windowCount;

	/**
	 * A counter at zero.
	 * @param timeline the run's timeline
	 */
	public Counter(Timeline timeline) {
		this.timeline = timeline;
		this.buckets = new long[timeline.buckets()];
	}

	/**
	 * Counts one event.
	 * @param time when it happened
	 */
	public void increment(double time) {
		increment(time, 1);
	}

	/**
	 * Counts events.
	 * @param time when they happened
	 * @param count how many
	 */
	public void increment(double time, long count) {
		this.buckets[this.timeline.index(time)] += count;
		this.total += count;
		if (this.timeline.inWindow(time)) {
			this.windowCount += count;
		}
	}

	/**
	 * The events counted in each bucket.
	 * @return a copy of the counts, one per bucket
	 */
	public long[] buckets() {
		return this.buckets.clone();
	}

	/**
	 * The events counted in all.
	 * @return the total
	 */
	public long total() {
		return this.total;
	}

	/**
	 * The events counted inside the measurement window.
	 * @return the count
	 */
	public long windowCount() {
		return this.windowCount;
	}

	/**
	 * The rate in each bucket.
	 * @return events per second, one per bucket
	 */
	public double[] rateSeries() {
		double scale = MS_PER_SECOND / this.timeline.bucketMs();
		double[] rates = new double[this.buckets.length];
		for (int bucket = 0; bucket < rates.length; bucket++) {
			rates[bucket] = this.buckets[bucket] * scale;
		}
		return rates;
	}

	/**
	 * The rate over the measurement window.
	 * @return events per second, 0 for an empty window
	 */
	public double windowRate() {
		double windowMs = this.timeline.windowMs();
		return (windowMs > 0) ? this.windowCount * MS_PER_SECOND / windowMs : 0.0;
	}

}
