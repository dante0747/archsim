package io.archsim.engine.simulator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Small designs and workloads the simulator's own tests run.
 */
final class SmallDesigns {

	/**
	 * 100 requests a second, evenly, for 6 s after a 1 s warm-up, with up to 600 s to
	 * drain: a run whose chunks are quiet, and which ends once idle.
	 */
	static final String QUIET = """
			{"seed": 7, "warmupS": 1, "durationS": 6, "drainS": 600, "rps": 100,
			 "arrivalProcess": "deterministic"}""";

	/**
	 * The quiet workload at 1,000 requests a second: its first 3 s chunk holds 3,000
	 * requests.
	 */
	static final String BUSY = """
			{"seed": 7, "warmupS": 1, "durationS": 6, "drainS": 600, "rps": 1000,
			 "arrivalProcess": "deterministic"}""";

	/**
	 * 100 requests a second, evenly, for 6 s after a 1 s warm-up, simulated whole: too
	 * short for a quick check to sample.
	 */
	static final String STEADY = """
			{"seed": 7, "warmupS": 1, "durationS": 6, "rps": 100, "arrivalProcess": "deterministic"}""";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private SmallDesigns() {
	}

	/**
	 * Clients calling an API that answers in 20 ms.
	 * @param workload the workload, as JSON
	 * @return the design
	 */
	static JsonNode clientsCallingAnApi(String workload) {
		return design("""
				{"latencyMs": {"kind": "constant", "value": 20}}""", workload);
	}

	/**
	 * Clients calling an API that answers in 20 ms and refuses what comes above its rate
	 * limit.
	 * @param rateLimitRps the requests a second the API takes
	 * @param workload the workload, as JSON
	 * @return the design
	 */
	static JsonNode clientsCallingARateLimitedApi(int rateLimitRps, String workload) {
		return design("""
				{"latencyMs": {"kind": "constant", "value": 20}, "rateLimitRps": %d}""".formatted(rateLimitRps),
				workload);
	}

	private static JsonNode design(String api, String workload) {
		return JSON.readTree("""
				{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "case"},
				 "architecture": {"autoSize": false,
				   "nodes": [{"id": "clients", "type": "traffic_source", "config": {}},
				             {"id": "api", "type": "external_api", "config": %s}],
				   "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": %s}""".formatted(api, workload));
	}

}
