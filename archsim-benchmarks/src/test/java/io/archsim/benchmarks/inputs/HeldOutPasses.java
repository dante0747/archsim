package io.archsim.benchmarks.inputs;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Experiment;

/**
 * Two calibration passes and the auxiliary ones of a held-out experiment, shaped as a
 * calibration records them: 1,500 then 12,000 requests at 50 and 400 req/s, the service's
 * CPU 900 then 4,500 ms, PostgreSQL's 300 then 2,100 ms, and no idle pass (before
 * protocol v5).
 */
final class HeldOutPasses {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** A timing's mean over its median. */
	private static final double MEAN_OVER_MEDIAN = 1.1;

	/** A timing's p99 over its p95. */
	private static final double P99_OVER_P95 = 1.5;

	private static final int LOW_REQUESTS = 1500;

	private static final int HIGH_REQUESTS = 12_000;

	private HeldOutPasses() {
	}

	/**
	 * The passes.
	 * @param experiment the experiment
	 * @return its low and top passes and, when it calibrates one, its pass with
	 * synchronous_commit off
	 */
	static ObjectNode of(Experiment experiment) {
		ObjectNode passes = NODES.objectNode();
		passes.set("low", entry(50, 2.0, service(experiment, LOW_REQUESTS, 900.0), 300.0));
		passes.set("high", entry(400, 2.1, service(experiment, HIGH_REQUESTS, 4500.0), 2100.0));
		if (experiment.calibration().contains("write_async:1")) {
			ObjectNode noFlush = stats(30.0, 100, Map.of("db_write_async", new double[] { 0.8, 1.5 }));
			if (experiment.stack().equals("chain")) {
				ObjectNode gateway = stats(30.0, 100, Map.of());
				gateway.set("upstream", noFlush);
				noFlush = gateway;
			}
			passes.set("write_async:1", entry(400, 2.0, noFlush, 50.0));
		}
		return passes;
	}

	private static ObjectNode service(Experiment experiment, int requests, double cpuMs) {
		Map<String, double[]> own = new LinkedHashMap<>();
		own.put("handler", new double[] { 1.2, 3.0 });
		own.put("own", new double[] { 0.3, 0.9 });
		Map<String, double[]> database = new LinkedHashMap<>(own);
		database.put("db_read", new double[] { 0.6, 1.2 });
		database.put("db_write", new double[] { 2.0, 4.0 });
		if (experiment.stack().equals("chain")) {
			Map<String, double[]> gateway = new LinkedHashMap<>(own);
			gateway.put("upstream", new double[] { 1.0, 2.5 });
			ObjectNode stats = stats(cpuMs, requests, gateway);
			stats.set("upstream", stats(cpuMs * 0.8, requests, database));
			return stats;
		}
		if (experiment.stack().equals("cache-ttl")) {
			return cacheTtl(own, requests, cpuMs);
		}
		ObjectNode stats = stats(cpuMs, requests, database);
		if (experiment.runtime().equals("java") || experiment.runtime().equals("go")) {
			stats.putObject("gc").put("collections", 6).put("pauseMs", 18.0);
		}
		return stats;
	}

	private static ObjectNode cacheTtl(Map<String, double[]> own, int requests, double cpuMs) {
		Map<String, double[]> timings = new LinkedHashMap<>(own);
		timings.put("redis_get", new double[] { 0.2, 0.5 });
		timings.put("redis_set", new double[] { 0.2, 0.5 });
		timings.put("db_read", new double[] { 0.6, 1.2 });
		ObjectNode stats = stats(cpuMs, requests, timings);
		ObjectNode redis = stats.putObject("redis");
		redis.putObject("get").put("calls", requests).put("usec", requests * 3.0);
		redis.putObject("set").put("calls", requests / 3).put("usec", requests);
		return stats;
	}

	// a service's /stats: each timing's median and p95, its mean 10% above the median
	private static ObjectNode stats(double cpuMs, int requests, Map<String, double[]> timings) {
		ObjectNode stats = NODES.objectNode().put("requests", requests).put("cpuMs", cpuMs);
		stats.put("cpuMsPerRequest", cpuMs / requests);
		stats.putObject("counters");
		ObjectNode byName = stats.putObject("timings");
		timings.forEach((name, timing) -> byName.putObject(name)
			.put("count", requests)
			.put("mean", timing[0] * MEAN_OVER_MEDIAN)
			.put("median", timing[0])
			.put("p95", timing[1])
			.put("p99", timing[1] * P99_OVER_P95));
		return stats;
	}

	private static ObjectNode entry(int rate, double p50, ObjectNode service, double postgresCpuMs) {
		ObjectNode entry = NODES.objectNode().put("rateRps", rate);
		entry.putObject("k6").put("p50", p50).put("p95", p50 * 2).put("p99", p50 * 3).put("successRate", 1.0);
		entry.set("service", service);
		entry.putObject("containerCpuMs").put("postgres", postgresCpuMs);
		return entry;
	}

}
