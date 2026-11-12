package io.archsim.engine.simulator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.parity.Repository;

/**
 * The designs the simulator's reference checks run, and what they read in its reports:
 * one service as an M/M/c queue, and the templates.
 */
final class ReferenceDesigns {

	/** The M/M/c service's mean time, exponentially distributed. */
	static final double MEAN_SERVICE_MS = 10.0;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private ReferenceDesigns() {
	}

	/**
	 * Clients calling one service with exponential service times and room to queue: an
	 * M/M/c queue, measured after 20 s of warm-up.
	 * @param rps the arrivals a second
	 * @param workers the service's workers, c
	 * @param durationS the measured seconds
	 * @param seed the run's seed
	 * @return the design
	 */
	static JsonNode singleService(double rps, int workers, double durationS, long seed) {
		return JSON.readTree("""
				{"schemaVersion": "1.0", "kind": "archsim.architecture", "metadata": {"name": "test"},
				 "architecture": {
				   "nodes": [
				     {"id": "clients", "type": "traffic_source", "label": "clients",
				      "config": {"timeoutMs": 600000, "maxAttempts": 1, "retryBackoffMs": 10}},
				     {"id": "svc", "type": "service", "label": "svc",
				      "config": {"replicas": 1, "workersPerReplica": %d, "queueCapacity": 100000,
				                 "queueTimeoutMs": null, "serviceTimeMs": {"kind": "exponential", "mean": %s}}}],
				   "edges": [
				     {"id": "c-s", "source": "clients", "sourcePort": "out", "target": "svc", "targetPort": "in",
				      "config": {"requestTypes": ["read", "write"], "latencyMs": {"kind": "constant", "value": 0}}}]},
				 "workload": {"rps": %s, "durationS": %s, "warmupS": 20, "seed": %d, "readRatio": 1.0},
				 "scenarios": []}""".formatted(workers, MEAN_SERVICE_MS, rps, durationS, seed));
	}

	/**
	 * A template as shipped.
	 * @param file its file name in {@code examples/}
	 * @return the design
	 */
	static ObjectNode example(String file) {
		try {
			return (ObjectNode) JSON.readTree(Files.readString(Repository.examples().resolve(file)));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * A part's summary in a report.
	 * @param report the report
	 * @param id the part's id
	 * @return its summary
	 */
	static JsonNode summaryOf(JsonNode report, String id) {
		for (JsonNode node : report.required("nodes")) {
			if (node.required("id").stringValue().equals(id)) {
				return node.required("summary");
			}
		}
		throw new AssertionError("no part " + id + " in the report");
	}

}
