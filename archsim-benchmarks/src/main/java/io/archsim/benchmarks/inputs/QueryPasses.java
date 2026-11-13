package io.archsim.benchmarks.inputs;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.experiments.Experiment;

/**
 * The queries a service sent PostgreSQL in its calibration passes, and PostgreSQL's CPU
 * per query of each kind: one figure for each kind of query, or none, since the reads
 * would otherwise set a link that the writes' whole round trips would then pay again
 * (protocols before v5 have no CPU figure for the pass without the flush).
 *
 * @param timings the service's timings in the top pass
 * @param high the top pass's queries
 * @param low the low pass's queries
 * @param read the reads' round trip in the top pass, or {@code null} without reads
 * @param write the writes' round trip in the top pass, or {@code null} without writes
 * @param asyncWrite the writes' round trip with synchronous_commit off, or {@code null}
 * @param cpuAll PostgreSQL's CPU per query over the passes, or {@code null}
 * @param cpuWrites its CPU per write with synchronous_commit off, or {@code null}
 */
record QueryPasses(JsonNode timings, long high, long low, @Nullable JsonNode read, @Nullable JsonNode write,
		@Nullable JsonNode asyncWrite, @Nullable Double cpuAll, @Nullable Double cpuWrites) {

	/**
	 * The least share of the pass's CPU per query the reads keep when the writes' is
	 * taken off.
	 */
	private static final double READS_LEAST_SHARE = 0.25;

	private static final List<String> KINDS = List.of("db_read", "db_write");

	/**
	 * The queries of an experiment's calibration passes.
	 * @param experiment the experiment
	 * @param passes the calibration's passes
	 * @return the queries and PostgreSQL's CPU per query
	 */
	static QueryPasses of(Experiment experiment, JsonNode passes) {
		JsonNode high = passes.get("high");
		JsonNode low = passes.get("low");
		JsonNode service = client(experiment, high);
		JsonNode timings = service.get("timings");
		JsonNode lowTimings = client(experiment, low).get("timings");
		long queries = count(timings, timings);
		long lowQueries = count(timings, lowTimings);
		Double cpuAll = PostgresCpu.perQuery(passes, new PostgresCpu.Queries(high, queries),
				new PostgresCpu.Queries(low, lowQueries));
		JsonNode write = null;
		JsonNode asyncWrite = null;
		Double cpuWrites = null;
		if (timings.has("db_write")) {
			write = PassReadings.timing(service, "db_write");
			JsonNode asyncPass = PassReadings.pass(passes, "write_async:1");
			asyncWrite = PassReadings.timing(client(experiment, asyncPass), "db_write_async");
			cpuWrites = PostgresCpu.perQueryNetOfIdle(passes, asyncPass, asyncWrite.get("count").doubleValue());
		}
		boolean writesWithoutCpu = write != null && cpuWrites == null;
		if (cpuAll == null || writesWithoutCpu) {
			cpuAll = null;
			cpuWrites = null;
		}
		JsonNode read = timings.has("db_read") ? PassReadings.timing(service, "db_read") : null;
		return new QueryPasses(timings, queries, lowQueries, read, write, asyncWrite, cpuAll, cpuWrites);
	}

	/**
	 * PostgreSQL's CPU per read: the pass's per query, less its writes' share when both
	 * have a figure, and never under a quarter of the pass's.
	 * @return the CPU per read (ms), or {@code null} without a figure
	 */
	@Nullable Double cpuReads() {
		long writes = this.timings.has("db_write") ? this.timings.get("db_write").get("count").longValue() : 0;
		if (this.cpuAll == null || this.cpuWrites == null || writes == 0 || this.read == null) {
			return this.cpuAll;
		}
		double others = this.cpuAll * this.high - this.cpuWrites * writes;
		return Math.max(READS_LEAST_SHARE * this.cpuAll, others / Math.max(this.read.get("count").longValue(), 1));
	}

	// the queries of the kinds the top pass made, counted in a pass's timings
	private static long count(JsonNode top, JsonNode timings) {
		long count = 0;
		for (String kind : KINDS) {
			if (top.has(kind) && timings.has(kind)) {
				count += timings.get(kind).get("count").longValue();
			}
		}
		return count;
	}

	/**
	 * The {@code /stats} of the service that calls PostgreSQL in a calibration pass:
	 * chain's API, inside the gateway's, else the service k6 calls.
	 * @param experiment the experiment
	 * @param entry the pass
	 * @return its {@code /stats}
	 */
	static JsonNode client(Experiment experiment, JsonNode entry) {
		JsonNode service = entry.get("service");
		return experiment.stack().equals("chain") ? service.get("upstream") : service;
	}

}
