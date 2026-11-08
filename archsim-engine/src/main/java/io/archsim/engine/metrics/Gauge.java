package io.archsim.engine.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A level that changes over time (a queue's depth, busy workers), integrated exactly: the
 * area under it in each bucket and over the measurement window, and its highest value.
 */
public final class Gauge {

	private final Timeline timeline;

	private final LevelArea levels;

	private final long[] maxes;

	private long value;

	private double lastTime;

	private long windowPeak;

	private double finishedAt;

	/**
	 * A gauge at zero.
	 * @param timeline the run's timeline
	 */
	public Gauge(Timeline timeline) {
		this.timeline = timeline;
		this.levels = new LevelArea(timeline);
		this.maxes = new long[timeline.buckets()];
	}

	/**
	 * Changes the level.
	 * @param time when
	 * @param delta how much it rises (or falls, when negative)
	 */
	public void add(double time, long delta) {
		if (time > this.lastTime) {
			advance(time);
		}
		this.value += delta;
		if (delta > 0) {
			int bucket = this.timeline.index(time);
			this.maxes[bucket] = Math.max(this.maxes[bucket], this.value);
			if (this.value > this.windowPeak && this.timeline.inWindow(time)) {
				this.windowPeak = this.value;
			}
		}
	}

	/**
	 * Integrates the level up to the end of the run.
	 * @param end the run's end
	 */
	public void finish(double end) {
		advance(end);
		this.finishedAt = end;
	}

	/**
	 * The level now.
	 * @return the level
	 */
	public long value() {
		return this.value;
	}

	/**
	 * The mean level in each bucket.
	 * @return one mean per bucket
	 */
	public double[] meanSeries() {
		double[] means = this.levels.area();
		for (int bucket = 0; bucket < means.length; bucket++) {
			means[bucket] /= this.timeline.bucketMs();
		}
		return means;
	}

	/**
	 * The mean level in each bucket over the part of it the run simulated, up to the end
	 * the gauge was finished at, so the last bucket, cut short, does not look like a
	 * drop.
	 * @return one mean per bucket; {@code null} for a bucket the run never reached
	 */
	public List<@Nullable Double> meanSeriesUntilEnd() {
		double[] area = this.levels.area();
		List<@Nullable Double> means = new ArrayList<>(area.length);
		for (int bucket = 0; bucket < area.length; bucket++) {
			double covered = Math.min(this.timeline.bucketMs(),
					this.finishedAt - (double) bucket * this.timeline.bucketMs());
			means.add((covered > 0) ? area[bucket] / covered : null);
		}
		return means;
	}

	/**
	 * The highest level in each bucket.
	 * @return a copy of the highest levels, one per bucket
	 */
	public long[] maxSeries() {
		return this.maxes.clone();
	}

	/**
	 * The highest level over the whole run, outside the measurement window too.
	 * @return the level
	 */
	public long runPeak() {
		return Arrays.stream(this.maxes).max().orElseThrow();
	}

	/**
	 * The area under the level in each bucket.
	 * @return a copy of the areas (level times milliseconds), one per bucket
	 */
	public double[] area() {
		return this.levels.area();
	}

	/**
	 * The area under the level over the measurement window.
	 * @return level times milliseconds
	 */
	public double windowArea() {
		return this.levels.windowArea();
	}

	/**
	 * The highest level inside the measurement window.
	 * @return the level
	 */
	public long windowPeak() {
		return this.windowPeak;
	}

	/**
	 * The mean level over the measurement window.
	 * @return the mean, 0 for an empty window
	 */
	public double windowMean() {
		return this.levels.windowMean();
	}

	private void advance(double time) {
		double last = this.lastTime;
		if (time <= last) {
			return;
		}
		if (this.value != 0) {
			// the buckets the span reached, as the integration split it: its last cannot
			// be told exactly from the time alone
			int reached = this.levels.add((double) this.value, last, time);
			for (int bucket = this.timeline.index(last); bucket <= reached; bucket++) {
				this.maxes[bucket] = Math.max(this.maxes[bucket], this.value);
			}
			if (this.levels.overlapsWindow(last, time)) {
				this.windowPeak = Math.max(this.windowPeak, this.value);
			}
		}
		this.lastTime = time;
	}

}
