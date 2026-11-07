package io.archsim.engine.calibration.prometheus;

import java.util.Optional;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.calibration.mapping.TimeRange;

/**
 * Where calibration reads Prometheus range queries from: a server, or responses recorded
 * earlier.
 */
public interface PrometheusClient {

	/**
	 * What the provenance names as the telemetry's source.
	 * @return the source, such as a server's address or a recording's name
	 */
	String source();

	/**
	 * A range query's series.
	 * @param query the PromQL query
	 * @param start the range's start, in seconds since the epoch
	 * @param end the range's end, in seconds since the epoch
	 * @param step the seconds between points
	 * @return the matrix's series, each with its {@code metric} and {@code values}
	 * @throws PrometheusException when the query fails or the response is not a matrix
	 */
	JsonNode queryRange(String query, double start, double end, double step);

	/**
	 * The range a recording was taken over, which calibration uses in place of the one
	 * asked for: the data came from there.
	 * @return the range; empty for a server, which answers for any range
	 */
	default Optional<TimeRange> recordedRange() {
		return Optional.empty();
	}

}
