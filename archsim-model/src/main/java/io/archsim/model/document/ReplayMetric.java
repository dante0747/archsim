package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/** The metric a calibration replay check compares. */
public enum ReplayMetric implements JsonEnum {

	/** The median latency, in milliseconds. */
	P50("p50"),
	/** The 95th percentile latency, in milliseconds. */
	P95("p95"),
	/** The 99th percentile latency, in milliseconds. */
	P99("p99"),
	/** The throughput, in requests per second. */
	THROUGHPUT_RPS("throughputRps"),
	/** The CPU in use, in mean cores over all replicas (schema 1.5). */
	CPU_CORES("cpuCores"),
	/** The memory peak per replica, in MiB (schema 1.5). */
	MEMORY_MIB("memoryMiB");

	private final String json;

	ReplayMetric(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
