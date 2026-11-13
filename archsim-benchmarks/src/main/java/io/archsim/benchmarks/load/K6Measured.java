package io.archsim.benchmarks.load;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;

/**
 * The window k6 measured, from its end-of-run summary: the latency of the requests that
 * succeeded, how many were sent, dropped, failed and succeeded, and the throughput. With
 * no request succeeding k6 still reports the latency trend, as zeros; that is no latency
 * at all, so the percentiles and the mean are None (run 4 recorded such repeats as 0 ms).
 */
public final class K6Measured {

	/** The latency of the measured scenario's requests that succeeded. */
	private static final String SUCCEEDED = "http_req_duration{scenario:measure,expected_response:true}";

	/** The measured scenario's requests sent. */
	private static final String SENT = "http_reqs{scenario:measure}";

	/** The measured scenario's requests that failed ({@code passes}: the failures). */
	private static final String FAILED = "http_req_failed{scenario:measure}";

	/** The measured scenario's requests no virtual user was free to send. */
	private static final String DROPPED = "dropped_iterations{scenario:measure}";

	private K6Measured() {
	}

	/**
	 * A summary's measured window.
	 * @param summary k6's summary ({@code data.metrics})
	 * @param duration the seconds it measured
	 * @return {@code p50}, {@code p95}, {@code p99}, {@code mean}, {@code sent},
	 * {@code dropped}, {@code failed}, {@code succeeded}, {@code successRate} (None when
	 * nothing was attempted) and {@code throughputRps}
	 */
	public static ObjectNode of(JsonNode summary, int duration) {
		JsonNode succeeded = values(summary, SUCCEEDED);
		long sent = count(values(summary, SENT), "count");
		long dropped = count(values(summary, DROPPED), "count");
		long failed = count(values(summary, FAILED), "passes");
		long ok = count(succeeded, "count");
		long attempted = sent + dropped;
		JsonNode latency = (ok > 0) ? succeeded : JsonNodeFactory.instance.objectNode();
		ObjectNode measured = JsonNodeFactory.instance.objectNode();
		measured.set("p50", PythonValues.get(latency, "med"));
		measured.set("p95", PythonValues.get(latency, "p(95)"));
		measured.set("p99", PythonValues.get(latency, "p(99)"));
		measured.set("mean", PythonValues.get(latency, "avg"));
		measured.set("sent", PythonValues.integer(sent));
		measured.set("dropped", PythonValues.integer(dropped));
		measured.set("failed", PythonValues.integer(failed));
		measured.set("succeeded", PythonValues.integer(ok));
		measured.set("successRate",
				(attempted > 0) ? PythonValues.number((double) ok / attempted) : PythonValues.none());
		measured.set("throughputRps", PythonValues.number((double) ok / duration));
		return measured;
	}

	private static JsonNode values(JsonNode summary, String metric) {
		return summary.path(metric).path("values");
	}

	// int() of a count k6 wrote
	private static long count(JsonNode values, String key) {
		return (long) values.path(key).asDouble(0);
	}

}
