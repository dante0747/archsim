package io.archsim.engine.report;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A run's figures per time bucket, from the start of the run; a series has a gap
 * ({@code null}) where a bucket measured nothing, and none where the report has no
 * series.
 *
 * @param bucketS how long a bucket is, in seconds
 * @param t each bucket's start, in seconds from the run's start
 * @param throughputRps the requests that succeeded per second
 * @param offeredRps the requests offered per second
 * @param errorRps the requests that failed per second
 * @param timeoutRps the requests that timed out per second
 * @param latencyP95Ms the p95 latency, in milliseconds
 */
public record ReportTimeseries(double bucketS, List<@Nullable Double> t, List<@Nullable Double> throughputRps,
		List<@Nullable Double> offeredRps, List<@Nullable Double> errorRps, List<@Nullable Double> timeoutRps,
		List<@Nullable Double> latencyP95Ms) {

	/**
	 * Copies the series.
	 */
	public ReportTimeseries {
		t = ReportJson.series(t);
		throughputRps = ReportJson.series(throughputRps);
		offeredRps = ReportJson.series(offeredRps);
		errorRps = ReportJson.series(errorRps);
		timeoutRps = ReportJson.series(timeoutRps);
		latencyP95Ms = ReportJson.series(latencyP95Ms);
	}

}
