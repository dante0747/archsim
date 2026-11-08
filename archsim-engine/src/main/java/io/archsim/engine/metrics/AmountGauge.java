package io.archsim.engine.metrics;

/**
 * An amount that changes over time and need not be whole (the vCPU or memory a cluster's
 * pods request), integrated exactly as a {@link Gauge} integrates its level: the area
 * under it in each bucket and over the measurement window.
 */
public final class AmountGauge {

	private final LevelArea levels;

	private double value;

	private double lastTime;

	/**
	 * An amount of zero.
	 * @param timeline the run's timeline
	 */
	public AmountGauge(Timeline timeline) {
		this.levels = new LevelArea(timeline);
	}

	/**
	 * Changes the amount.
	 * @param time when
	 * @param delta how much it rises (or falls, when negative)
	 */
	public void add(double time, double delta) {
		if (time > this.lastTime) {
			advance(time);
		}
		this.value += delta;
	}

	/**
	 * Integrates the amount up to the end of the run.
	 * @param end the run's end
	 */
	public void finish(double end) {
		advance(end);
	}

	/**
	 * The area under the amount in each bucket.
	 * @return a copy of the areas (amount times milliseconds), one per bucket
	 */
	public double[] area() {
		return this.levels.area();
	}

	/**
	 * The mean amount over the measurement window.
	 * @return the mean, 0 for an empty window
	 */
	public double windowMean() {
		return this.levels.windowMean();
	}

	private void advance(double time) {
		if (time <= this.lastTime) {
			return;
		}
		if (this.value != 0) {
			this.levels.add(this.value, this.lastTime, time);
		}
		this.lastTime = time;
	}

}
