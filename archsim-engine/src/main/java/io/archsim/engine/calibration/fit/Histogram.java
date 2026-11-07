package io.archsim.engine.calibration.fit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * Cumulative counts (or rates) per finite upper bound, in milliseconds, plus the total
 * (the {@code +Inf} bucket): a Prometheus histogram over a range.
 *
 * @param bounds the finite upper bounds, ascending, in milliseconds
 * @param cumulative the cumulative count at each bound
 * @param total every observation, the {@code +Inf} bucket's count
 */
public record Histogram(List<Double> bounds, List<Double> cumulative, double total) {

	/** Relative slack when a cumulative count is taken to have reached the total. */
	static final double FULL_EPS = 1e-9;

	/**
	 * Copies the bounds and counts, which must pair up.
	 */
	public Histogram {
		bounds = List.copyOf(bounds);
		cumulative = List.copyOf(cumulative);
		if (bounds.size() != cumulative.size()) {
			throw new IllegalArgumentException("each bound needs its cumulative count");
		}
	}

	/**
	 * A histogram from its buckets.
	 * @param buckets each bucket's upper bound ({@code le}, infinite for {@code +Inf})
	 * and its cumulative count
	 * @param scale milliseconds per unit of the bounds
	 * @return the histogram; counts never fall from one bound to the next (scrapes
	 * jitter)
	 */
	public static Histogram fromBuckets(Map<Double, Double> buckets, double scale) {
		TreeMap<Double, Double> finite = new TreeMap<>();
		buckets.forEach((bound, count) -> {
			if (Double.isFinite(bound)) {
				finite.put(bound, count);
			}
		});
		Double infinite = buckets.get(Double.POSITIVE_INFINITY);
		double total = (infinite != null) ? infinite : (finite.isEmpty() ? 0.0 : finite.lastEntry().getValue());
		List<Double> bounds = new ArrayList<>();
		List<Double> cumulative = new ArrayList<>();
		double running = 0.0;
		for (Map.Entry<Double, Double> bucket : finite.entrySet()) {
			running = Math.max(running, bucket.getValue());
			bounds.add(bucket.getKey() * scale);
			cumulative.add(running);
		}
		return new Histogram(bounds, cumulative, Math.max(total, running));
	}

	/**
	 * A quantile as Prometheus' {@code histogram_quantile} gives it: linear inside the
	 * bucket that holds it, and the highest finite bound for one in the {@code +Inf}
	 * bucket.
	 * @param quantile the quantile, such as 0.95
	 * @return its value in milliseconds; empty without observations or bounds
	 */
	public OptionalDouble quantile(double quantile) {
		if (this.total <= 0 || this.bounds.isEmpty()) {
			return OptionalDouble.empty();
		}
		double rank = quantile * this.total;
		double lowerBound = 0.0;
		double lowerCount = 0.0;
		for (int i = 0; i < this.bounds.size(); i++) {
			double bound = this.bounds.get(i);
			double count = this.cumulative.get(i);
			if (count >= rank) {
				return OptionalDouble.of((count == lowerCount) ? bound
						: lowerBound + (bound - lowerBound) * (rank - lowerCount) / (count - lowerCount));
			}
			lowerBound = bound;
			lowerCount = count;
		}
		return OptionalDouble.of(this.bounds.getLast());
	}

	/**
	 * An estimate of the mean from the buckets' midpoints, the {@code +Inf} bucket's
	 * observations at the highest finite bound.
	 * @return the mean, in milliseconds; 0 without observations
	 */
	public double meanUpper() {
		if (this.total <= 0) {
			return 0.0;
		}
		double sum = 0.0;
		double previousBound = 0.0;
		double previousCount = 0.0;
		for (int i = 0; i < this.bounds.size(); i++) {
			sum += (this.cumulative.get(i) - previousCount) * (previousBound + this.bounds.get(i)) / 2;
			previousBound = this.bounds.get(i);
			previousCount = this.cumulative.get(i);
		}
		sum += (this.total - previousCount) * previousBound;
		return sum / this.total;
	}

	/**
	 * The share of observations above the highest finite bound: only the {@code +Inf}
	 * bucket holds them, so how far above is unknown.
	 * @return the share; 0 when it is within float noise of none
	 */
	double beyondShare() {
		if (this.total <= 0 || this.bounds.isEmpty()) {
			return 0.0;
		}
		double share = (this.total - this.cumulative.getLast()) / this.total;
		return (share > FULL_EPS) ? share : 0.0;
	}

	/**
	 * The bounds of the bucket that holds a quantile.
	 * @param quantile the quantile
	 * @return its lower and upper bound; empty in the {@code +Inf} bucket or without
	 * observations
	 */
	Optional<Bucket> bucketOf(double quantile) {
		if (this.total <= 0) {
			return Optional.empty();
		}
		double rank = quantile * this.total;
		double lower = 0.0;
		for (int i = 0; i < this.bounds.size(); i++) {
			if (this.cumulative.get(i) >= rank) {
				return Optional.of(new Bucket(lower, this.bounds.get(i)));
			}
			lower = this.bounds.get(i);
		}
		return Optional.empty();
	}

	/**
	 * One bucket's bounds.
	 *
	 * @param lower its lower bound, in milliseconds
	 * @param upper its upper bound, in milliseconds
	 */
	record Bucket(double lower, double upper) {

	}

}
