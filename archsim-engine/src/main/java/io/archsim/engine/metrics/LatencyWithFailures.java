package io.archsim.engine.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import io.archsim.engine.numeric.FloatSum;

/**
 * What users waited, failures included: successes at their latency, and each failure as a
 * full client timeout. A failure that outlasted its timeout (retries) counts as long as
 * it took; one that ended within it is kept at the timeout exactly, so a percentile that
 * lands on failures reads as the timeout itself rather than a bin near it.
 */
public final class LatencyWithFailures {

	/** How far past its timeout a failure may end and still count as the timeout. */
	private static final double TIMEOUT_SLACK_MS = 1e-6;

	private static final double PERCENT = 100.0;

	private final LatencyHistogram histogram = new LatencyHistogram();

	private final Map<Double, Long> atTimeout = new LinkedHashMap<>();

	/**
	 * Records a success.
	 * @param latency how long it took, in milliseconds
	 */
	public void success(double latency) {
		this.histogram.record(latency);
	}

	/**
	 * Records a failure.
	 * @param latency how long it took, in milliseconds
	 * @param timeoutMs the client's timeout
	 */
	public void failure(double latency, double timeoutMs) {
		if (latency <= timeoutMs + TIMEOUT_SLACK_MS) {
			this.atTimeout.merge(timeoutMs, 1L, Long::sum);
		}
		else {
			this.histogram.record(latency);
		}
	}

	/**
	 * How many requests were recorded.
	 * @return successes and failures
	 */
	public long count() {
		return this.histogram.count() + this.atTimeout.values().stream().mapToLong(Long::longValue).sum();
	}

	/**
	 * The latency at a percentile.
	 * @param percentile from 0 to 100; 100 and above give the largest
	 * @return milliseconds, empty when nothing was recorded
	 */
	public OptionalDouble percentile(double percentile) {
		long count = count();
		if (count == 0) {
			return OptionalDouble.empty();
		}
		if (percentile >= PERCENT) {
			return OptionalDouble.of(largest());
		}
		List<Point> points = points();
		double rank = percentile / PERCENT * count;
		long seen = 0;
		for (Point point : points) {
			seen += point.count();
			if (seen >= rank && seen > 0) {
				return OptionalDouble.of(point.latency());
			}
		}
		return OptionalDouble.of(points.getLast().latency());
	}

	/**
	 * The count, mean, p50, p90, p95, p99 and largest latency, each rounded to 4
	 * decimals.
	 * @return the summary
	 */
	public LatencySummary summary() {
		long count = count();
		double total = this.histogram.sum() + FloatSum
			.of(this.atTimeout.entrySet().stream().mapToDouble((failures) -> failures.getKey() * failures.getValue()));
		OptionalDouble mean = (count != 0) ? OptionalDouble.of(total / count) : OptionalDouble.empty();
		OptionalDouble largest = (count != 0) ? OptionalDouble.of(largest()) : OptionalDouble.empty();
		return LatencySummary.of(count, mean, this::percentile, largest);
	}

	private double largest() {
		double largest = (this.histogram.count() != 0) ? this.histogram.max() : 0.0;
		for (double timeout : this.atTimeout.keySet()) {
			largest = Math.max(largest, timeout);
		}
		return largest;
	}

	private List<Point> points() {
		List<Point> points = new ArrayList<>();
		this.histogram.forEachBin((latency, count) -> points.add(new Point(latency, count)));
		this.atTimeout.forEach((timeout, count) -> points.add(new Point(timeout, count)));
		points.sort(Comparator.comparingDouble(Point::latency));
		return points;
	}

	private record Point(double latency, long count) {

	}

}
