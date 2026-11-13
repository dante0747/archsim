package io.archsim.benchmarks.designs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * Simulations of the benchmark designs on the engine, in this process, and what the
 * harness reads from their reports: latency, success, throughput, each node's CPU and
 * memory, and the database's, cache's and queue's own figures.
 */
public final class Simulations {

	/**
	 * Wall-clock limit of one simulation (the engine's default guards the API server): an
	 * overloaded step holds up to 100,000 queued requests, which takes minutes when other
	 * work shares the CPU; the limit only decides whether a run finishes, never what it
	 * returns.
	 */
	static final double WALL_SECONDS = 3600.0;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final double MIB_PER_GIB = 1024;

	private static final int CORES_DECIMALS = 4;

	private static final int RATE_DECIMALS = 2;

	private static final List<String> DATABASE_KEYS = List.of("commitsPerFlush", "meanLockWaitMs", "meanCommitWaitMs");

	private final Simulator simulator;

	private final RunRequest request;

	/**
	 * Simulations on an engine.
	 * @param simulator the engine
	 */
	public Simulations(Simulator simulator) {
		this.simulator = simulator;
		this.request = RunRequest.of(null)
			.withLimits(new SimulationLimits(SimulationLimits.DEFAULT.maxRequests(), WALL_SECONDS));
	}

	/**
	 * Simulations on the engine with its standard components and the prices it ships.
	 * @return the simulations
	 */
	public static Simulations onTheEngine() {
		return new Simulations(new Simulator(StandardComponents.registry(), PriceSnapshots.shipped()));
	}

	/**
	 * A design's simulation, as the harness reads it.
	 * @param design the design
	 * @return its offered load, latency percentiles, success rate, throughput, result
	 * digest, resources, and the database's, cache's and queue's figures when it has them
	 */
	public ObjectNode simulate(ObjectNode design) {
		ObjectNode report = this.simulator.run(design, this.request);
		JsonNode summary = report.get("summary");
		JsonNode latency = summary.get("latencyMs");
		ObjectNode out = NODES.objectNode().set("offeredRps", design.at("/workload/rps"));
		for (String key : new String[] { "p50", "p95", "p99", "mean" }) {
			out.set(key, PythonValues.get(latency, key));
		}
		out.set("successRate", PythonValues.get(summary, "successRate"));
		out.set("throughputRps", PythonValues.get(summary, "throughputRps"));
		out.set("resultDigest", report.at("/provenance/resultDigest"));
		out.set("resources", resources(design, report));
		Map<String, JsonNode> nodes = new HashMap<>();
		report.get("nodes").forEach((node) -> nodes.put(node.get("id").stringValue(), node.get("summary")));
		parts(design, nodes, out);
		return out;
	}

	/**
	 * The highest load at which a design meets the objective, by doubling then bisection
	 * to 1%; with a knee already found for nearly the same inputs, by 25% steps from it.
	 * @param experiment the experiment
	 * @param inputs its model inputs
	 * @param near a knee already found for nearly the same inputs, or None
	 * @param window the simulated seconds of each load
	 * @return {@code kneeRps} and the loads {@code tried}
	 */
	public ObjectNode knee(Experiment experiment, JsonNode inputs, JsonNode near, Designs.Window window) {
		return KneeSearch.knee((rps) -> simulate(Designs.design(experiment, inputs, rps, window)), near);
	}

	private static void parts(ObjectNode design, Map<String, JsonNode> nodes, ObjectNode out) {
		if (nodes.containsKey("db")) {
			ObjectNode database = out.putObject("database");
			DATABASE_KEYS.forEach((key) -> database.set(key, PythonValues.get(nodes.get("db"), key)));
		}
		if (nodes.containsKey("cache")) {
			out.set("cacheHitRatio", PythonValues.get(nodes.get("cache"), "hitRatio"));
		}
		if (nodes.containsKey("kafka")) {
			JsonNode kafka = nodes.get("kafka");
			JsonNode processed = kafka.path("counters").path("processed");
			JsonNode workload = design.get("workload");
			JsonNode seconds = PythonValues.subtract(workload.get("durationS"), workload.get("warmupS"));
			ObjectNode queue = out.putObject("queue");
			queue.set("consumedRps", PythonValues.round(
					PythonValues.divide(processed.isMissingNode() ? PythonValues.integer(0) : processed, seconds),
					RATE_DECIMALS));
			for (String key : new String[] { "backlogAtEnd", "meanMessageAgeMs", "p95MessageAgeMs" }) {
				queue.set(key, PythonValues.get(kafka, key));
			}
		}
	}

	// each node's busy cores (its CPU utilization times its cores) and memory (MiB): a
	// service's peak, the cache's keys at the end times the memory per key
	static ObjectNode resources(ObjectNode design, ObjectNode report) {
		Map<String, JsonNode> configs = new HashMap<>();
		design.at("/architecture/nodes")
			.forEach((node) -> configs.put(node.get("id").stringValue(), node.get("config")));
		ObjectNode out = NODES.objectNode();
		for (JsonNode node : report.get("nodes")) {
			ObjectNode part = resources(node.get("summary"),
					configs.getOrDefault(node.get("id").stringValue(), NODES.objectNode()));
			if (!part.isEmpty()) {
				out.set(node.get("id").stringValue(), part);
			}
		}
		return out;
	}

	// one node's: its CPU when it has cores, its memory when it reports any
	private static ObjectNode resources(JsonNode summary, JsonNode config) {
		ObjectNode part = NODES.objectNode();
		JsonNode utilization = PythonValues.get(summary, "cpuCoreUtilization");
		if (!PythonValues.isNone(utilization) && PythonValues.numberOrZero(config, "cpuCores") != 0) {
			JsonNode replicas = config.has("replicas") ? config.get("replicas") : PythonValues.integer(1);
			part.set("cores",
					PythonValues.round(
							PythonValues.multiply(PythonValues.multiply(utilization, config.get("cpuCores")), replicas),
							CORES_DECIMALS));
		}
		JsonNode peak = PythonValues.get(summary, "memoryPeakGiB");
		if (!PythonValues.isNone(peak)) {
			part.put("memoryMiB", PythonRound.round(peak.doubleValue() * MIB_PER_GIB, 1));
		}
		JsonNode keys = PythonValues.get(summary, "keysCached");
		if (!PythonValues.isNone(keys) && PythonValues.numberOrZero(config, "itemSizeKb") != 0) {
			part.put("memoryMiB",
					PythonRound.round(keys.doubleValue() * config.get("itemSizeKb").doubleValue() / MIB_PER_GIB, 1));
		}
		return part;
	}

}
