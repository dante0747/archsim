package io.archsim.model.document;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.MaxItems;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinItems;
import io.archsim.model.codec.Tag;

/**
 * A replayed rate curve, such as traffic observed in telemetry: linear between its points
 * and flat after the last.
 *
 * @param points the curve, from {@code t = 0}, times rising strictly
 * @param source where it came from (and how much it was time-compressed)
 */
@Tag("trace")
@Description("""
		A replayed rate curve, e.g. observed traffic from telemetry: ``points`` are ``[t, rps]``
		pairs (seconds from the start of the run), linear between points and flat after the last.
		``source`` says where it came from (and how much it was time-compressed).""")
public record TracePattern(@MinItems(2) @MaxItems(2000) List<TracePoint> points,
		@MaxLength(500) @Nullable String source) implements TrafficPattern {

	/** The highest rate a trace may have, in requests per second. */
	private static final double MAX_RPS = 100_000;

	/**
	 * Copies the points, and checks the curve starts at {@code t = 0}, its times rise
	 * strictly and its rates are within bounds. Each comparison is written so that NaN
	 * fails it.
	 */
	public TracePattern {
		points = List.copyOf(points);
		if (points.getFirst().t() != 0.0) {
			throw new IllegalArgumentException("a trace starts at t = 0");
		}
		for (int i = 1; i < points.size(); i++) {
			if (!(points.get(i).t() > points.get(i - 1).t())) {
				throw new IllegalArgumentException("trace times must rise strictly");
			}
		}
		for (TracePoint point : points) {
			if (!(0 <= point.rps() && point.rps() <= MAX_RPS)) {
				throw new IllegalArgumentException("trace rates must be between 0 and 100,000 req/s");
			}
		}
	}

	/**
	 * The offered rate at a moment of the run: linear between points, flat after the
	 * last.
	 * @param seconds the moment, in seconds from the start of the run
	 * @return the rate, in requests per second
	 */
	public double rate(double seconds) {
		TracePoint last = this.points.getLast();
		if (seconds >= last.t()) {
			return last.rps();
		}
		int low = 0;
		int high = this.points.size() - 1;
		while (high - low > 1) {
			int middle = (low + high) / 2;
			if (this.points.get(middle).t() <= seconds) {
				low = middle;
			}
			else {
				high = middle;
			}
		}
		TracePoint before = this.points.get(low);
		TracePoint after = this.points.get(high);
		return before.rps() + (after.rps() - before.rps()) * (seconds - before.t()) / (after.t() - before.t());
	}

	/**
	 * The expected arrivals from 0 to {@code until} seconds: the area under the curve.
	 * @param until the end of the span, in seconds from the start of the run
	 * @return the expected number of requests
	 */
	public double area(double until) {
		double total = 0.0;
		for (int i = 1; i < this.points.size(); i++) {
			TracePoint start = this.points.get(i - 1);
			if (start.t() >= until) {
				break;
			}
			double end = Math.min(this.points.get(i).t(), until);
			total += (start.rps() + rate(end)) / 2 * (end - start.t());
		}
		TracePoint last = this.points.getLast();
		if (until > last.t()) {
			total += last.rps() * (until - last.t());
		}
		return total;
	}

}
