package io.archsim.engine.metrics;

import io.archsim.engine.numeric.FloorDivision;

/**
 * A run's time cut into buckets of a round width, from 0 to its end (drain included),
 * with the measurement window (warm-up excluded) inside it. Times are simulated
 * milliseconds.
 */
public final class Timeline {

	private static final int[] ROUND_WIDTHS_MS = { 100, 250, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000,
			120_000 };

	/** The most buckets across the arrival period, from 0 to the end of the window. */
	private static final int MAX_ARRIVAL_BUCKETS = 90;

	/** The most buckets across the whole run, drain included. */
	private static final int MAX_BUCKETS = 1200;

	private final int bucketMs;

	private final int buckets;

	private final double windowStart;

	private final double windowEnd;

	/**
	 * The timeline of a run.
	 * @param endMs when the run ends, drain included
	 * @param windowStart when the measurement window starts
	 * @param windowEnd when it ends
	 */
	public Timeline(double endMs, double windowStart, double windowEnd) {
		this.bucketMs = bucketWidth(windowEnd, endMs);
		this.buckets = Math.max(1, (int) Math.ceil(endMs / this.bucketMs));
		this.windowStart = windowStart;
		this.windowEnd = windowEnd;
	}

	/**
	 * The narrowest round width giving at most 90 buckets over the arrival period and
	 * 1,200 over the run.
	 * @param arrivalMs the arrival period, from 0 to the end of the window
	 * @param totalMs the run's length, drain included
	 * @return the width in milliseconds, 120 s when none is wide enough
	 */
	static int bucketWidth(double arrivalMs, double totalMs) {
		for (int width : ROUND_WIDTHS_MS) {
			if (arrivalMs / width <= MAX_ARRIVAL_BUCKETS && totalMs / width <= MAX_BUCKETS) {
				return width;
			}
		}
		return ROUND_WIDTHS_MS[ROUND_WIDTHS_MS.length - 1];
	}

	/**
	 * The bucket a time falls in; times past the last bucket fall in it.
	 * @param time the time
	 * @return the bucket's index
	 */
	public int index(double time) {
		double bucket = FloorDivision.of(time, this.bucketMs);
		return (bucket < this.buckets) ? (int) bucket : this.buckets - 1;
	}

	/**
	 * Whether a time falls in the measurement window.
	 * @param time the time
	 * @return {@code true} from the window's start, up to but not including its end
	 */
	public boolean inWindow(double time) {
		return this.windowStart <= time && time < this.windowEnd;
	}

	/**
	 * The width of a bucket.
	 * @return milliseconds
	 */
	public int bucketMs() {
		return this.bucketMs;
	}

	/**
	 * How many buckets the run has.
	 * @return at least one
	 */
	public int buckets() {
		return this.buckets;
	}

	/**
	 * When the measurement window starts.
	 * @return milliseconds
	 */
	public double windowStart() {
		return this.windowStart;
	}

	/**
	 * When the measurement window ends.
	 * @return milliseconds
	 */
	public double windowEnd() {
		return this.windowEnd;
	}

	/**
	 * The window's length.
	 * @return milliseconds
	 */
	public double windowMs() {
		return this.windowEnd - this.windowStart;
	}

	/**
	 * The first whole bucket inside the measurement window: the first that starts in it.
	 * @return the bucket's index
	 */
	public int firstWindowBucket() {
		return (int) Math.ceil(this.windowStart / this.bucketMs);
	}

	/**
	 * Where the window's whole buckets end: past the last bucket that ends in the window,
	 * and past the first one at least, so a window shorter than a bucket has one.
	 * @return the index past the last bucket, which may be past the timeline's end
	 */
	public int windowBucketsEnd() {
		return Math.max(firstWindowBucket() + 1, (int) Math.floor(this.windowEnd / this.bucketMs));
	}

}
