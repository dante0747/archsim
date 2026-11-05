package io.archsim.engine.findings;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.numeric.TrueDivision;
import io.archsim.engine.report.ReportTimeseries;
import io.archsim.engine.report.RunReport;

/** A run's client-side time series, read over ranges of buckets. */
final class ClientSeries {

	/** Absorbs the rounding of a moment divided by the bucket length. */
	private static final double EPSILON = 1e-9;

	private final ReportTimeseries series;

	private final double bucket;

	private final int buckets;

	private final int firstMeasured;

	private final int end;

	/**
	 * The series of a run.
	 * @param report the run's report
	 * @throws ArithmeticException when its buckets have no length
	 */
	ClientSeries(RunReport report) {
		this.series = report.timeseries();
		this.bucket = this.series.bucketS();
		this.buckets = this.series.t().size();
		this.firstMeasured = Math.min(this.buckets,
				(int) Math.ceil(TrueDivision.of(report.window().warmupS(), this.bucket)));
		this.end = Math.min(this.buckets,
				Math.max(1, (int) Math.ceil(TrueDivision.of(report.window().durationS(), this.bucket))));
	}

	/**
	 * The length of a bucket.
	 * @return its length, in seconds
	 */
	double bucket() {
		return this.bucket;
	}

	/**
	 * The first bucket after the warm-up.
	 * @return its index
	 */
	int firstMeasured() {
		return this.firstMeasured;
	}

	/**
	 * The bucket after the last one of the offered load.
	 * @return its index
	 */
	int end() {
		return this.end;
	}

	/**
	 * The bucket a moment falls in.
	 * @param seconds the moment, in seconds from the run's start
	 * @return its bucket, within the run
	 */
	int index(double seconds) {
		return Math.clamp((long) Math.floor(seconds / this.bucket + EPSILON), 0, this.buckets);
	}

	/**
	 * The first bucket that starts at or after a moment.
	 * @param seconds the moment, in seconds from the run's start
	 * @return the bucket, within the run
	 */
	int indexAfter(double seconds) {
		return Math.clamp((long) Math.ceil(seconds / this.bucket - EPSILON), 0, this.buckets);
	}

	/**
	 * When a bucket starts.
	 * @param index the bucket
	 * @return its start, in seconds from the run's start
	 */
	double start(int index) {
		return Objects.requireNonNull(this.series.t().get(index), "a bucket has its start");
	}

	/**
	 * The share of requests that succeeded over some buckets.
	 * @param from the first bucket
	 * @param to the bucket after the last
	 * @return the share; {@code null} when no request finished
	 */
	@Nullable Double share(int from, int to) {
		double succeeded = 0.0;
		double failed = 0.0;
		for (int i = Math.max(0, from); i < Math.min(this.buckets, to); i++) {
			succeeded += orZero(this.series.throughputRps(), i);
			failed += orZero(this.series.errorRps(), i) + orZero(this.series.timeoutRps(), i);
		}
		return (succeeded + failed > 0) ? succeeded / (succeeded + failed) : null;
	}

	/**
	 * The mean p95 latency over some buckets, the buckets without one left out.
	 * @param from the first bucket
	 * @param to the bucket after the last
	 * @return the mean, in milliseconds; {@code null} when no bucket has one
	 */
	@Nullable Double p95(int from, int to) {
		int size = this.series.latencyP95Ms().size();
		List<Double> values = this.series.latencyP95Ms()
			.subList(Math.clamp(Math.max(0, from), 0, size),
					Math.clamp(Math.max(from, Math.min(this.buckets, to)), 0, size))
			.stream()
			.filter(Objects::nonNull)
			.toList();
		if (values.isEmpty()) {
			return null;
		}
		return FloatSum.of(values.stream().mapToDouble(Double::doubleValue)) / values.size();
	}

	private static double orZero(List<@Nullable Double> series, int index) {
		Double value = (index < series.size()) ? series.get(index) : null;
		return (value != null) ? value : 0.0;
	}

}
