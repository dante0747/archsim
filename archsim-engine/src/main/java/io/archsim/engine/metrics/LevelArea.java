package io.archsim.engine.metrics;

/**
 * The area under a level that holds between its changes, integrated exactly: in each
 * bucket of the run and over the measurement window. A {@link Gauge} and an
 * {@link AmountGauge} keep theirs in one.
 */
final class LevelArea {

	private final Timeline timeline;

	private final double[] area;

	private double windowArea;

	/**
	 * No area yet.
	 * @param timeline the run's timeline
	 */
	LevelArea(Timeline timeline) {
		this.timeline = timeline;
		this.area = new double[timeline.buckets()];
	}

	/**
	 * Adds the area under a level held from one time to a later one, split across the
	 * buckets it spans; time past the last bucket falls in it.
	 * @param level the level
	 * @param from when it started to hold
	 * @param to when it changed, later than {@code from}
	 * @return the last bucket the span reached
	 */
	int add(double level, double from, double to) {
		if (overlapsWindow(from, to)) {
			this.windowArea += level * (windowHigh(to) - windowLow(from));
		}
		int last = this.timeline.buckets() - 1;
		int bucket = this.timeline.index(from);
		double current = from;
		while (true) {
			double edge = (bucket >= last) ? to : (double) (bucket + 1) * this.timeline.bucketMs();
			double end = Math.min(to, edge);
			this.area[bucket] += level * (end - current);
			if (end >= to) {
				return bucket;
			}
			current = end;
			bucket++;
		}
	}

	/**
	 * Whether a span of time overlaps the measurement window.
	 * @param from its start
	 * @param to its end
	 * @return {@code true} when some of it falls in the window
	 */
	boolean overlapsWindow(double from, double to) {
		return windowHigh(to) > windowLow(from);
	}

	/**
	 * The area in each bucket.
	 * @return a copy of the areas (level times milliseconds), one per bucket
	 */
	double[] area() {
		return this.area.clone();
	}

	/**
	 * The area over the measurement window.
	 * @return level times milliseconds
	 */
	double windowArea() {
		return this.windowArea;
	}

	/**
	 * The mean level over the measurement window.
	 * @return the mean, 0 for an empty window
	 */
	double windowMean() {
		double windowMs = this.timeline.windowMs();
		return (windowMs > 0) ? this.windowArea / windowMs : 0.0;
	}

	private double windowLow(double from) {
		return Math.max(from, this.timeline.windowStart());
	}

	private double windowHigh(double to) {
		return Math.min(to, this.timeline.windowEnd());
	}

}
