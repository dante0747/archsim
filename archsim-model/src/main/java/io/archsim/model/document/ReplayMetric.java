package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/** The metric a calibration replay check compares. */
public enum ReplayMetric implements JsonEnum {

	P50("p50"), P95("p95"), P99("p99"), THROUGHPUT_RPS("throughputRps"), CPU_CORES("cpuCores"), MEMORY_MIB("memoryMiB");

	private final String json;

	ReplayMetric(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
