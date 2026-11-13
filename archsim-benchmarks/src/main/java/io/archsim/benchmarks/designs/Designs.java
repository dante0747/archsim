package io.archsim.benchmarks.designs;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.benchmarks.experiments.ServiceRuntime;
import io.archsim.benchmarks.values.PythonValues;

/**
 * The ArchSim design of an experiment's stack at one offered load, from its model inputs:
 * k6's clients, the service (chain: the gateway, then the API), and the stack's
 * PostgreSQL, Redis or Kafka, each node from the inputs of its part.
 */
public final class Designs {

	/** The Kafka stack's partitions ({@code compose.yaml}). */
	public static final int KAFKA_PARTITIONS = 6;

	/** The clients' timeout, as k6's. */
	static final int TIMEOUT_MS = 2000;

	/** The seed of every simulation. */
	static final int SEED = 42;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final List<String> DATABASE_STACKS = List.of("postgres", "java-mixed", "go-mixed", "python-mixed",
			"chain");

	private Designs() {
	}

	/**
	 * The design at one load.
	 * @param experiment the experiment
	 * @param inputs the model inputs, each with its {@code value}
	 * @param rps the offered load
	 * @param window the simulated seconds: warm-up, then measured
	 * @return the design
	 */
	public static ObjectNode design(Experiment experiment, JsonNode inputs, JsonNode rps, Window window) {
		ObjectNode values = values(inputs);
		ArrayNode nodes = NODES.arrayNode().add(clients("k6"));
		ArrayNode edges = NODES.arrayNode();
		services(experiment, values, nodes, edges);
		backends(experiment, values, nodes, edges);
		ObjectNode design = NODES.objectNode()
			.put("schemaVersion", values.has("host") ? "1.7" : "1.0")
			.put("kind", "archsim.architecture");
		design.putObject("metadata").put("name", "benchmark " + experiment.id());
		ObjectNode architecture = design.putObject("architecture");
		architecture.set("nodes", nodes);
		architecture.set("edges", edges);
		architecture.put("autoSize", false);
		if (values.has("host")) {
			architecture.set("host", values.get("host"));
		}
		design.set("workload", workload(experiment, rps, window));
		design.putArray("scenarios");
		return design;
	}

	/**
	 * The model inputs' values.
	 * @param inputs the inputs, each with its {@code value} and how it was found
	 * @return each input's value
	 */
	public static ObjectNode values(JsonNode inputs) {
		ObjectNode values = NODES.objectNode();
		inputs.properties().forEach((input) -> {
			if (input.getValue().isObject() && input.getValue().has("value")) {
				values.set(input.getKey(), input.getValue().get("value"));
			}
		});
		return values;
	}

	// the service under test, or chain's gateway calling its API, one after another
	private static void services(Experiment experiment, ObjectNode values, ArrayNode nodes, ArrayNode edges) {
		if (experiment.stack().equals("chain")) {
			ServiceRuntime nodejs = Experiments.standard().runtime(experiment);
			nodes.add(Nodes.service(values,
					new Nodes.Service("gateway", nodejs, "serviceTimeMs", "Node.js gateway", "service")));
			nodes
				.add(Nodes.service(values, new Nodes.Service("api", nodejs, "apiServiceTimeMs", "Node.js API", "api")));
			edges.add(Edge.of("e-clients", "clients", "gateway", values.get("clientLinkLatencyMs")).json());
			edges.add(Edge.of("e-api", "gateway", "api", values.get("apiLinkLatencyMs")).json());
			return;
		}
		ServiceRuntime runtime = Experiments.standard().runtime(experiment);
		nodes
			.add(Nodes.service(values, new Nodes.Service("api", runtime, "serviceTimeMs", runtime.label(), "service")));
		edges.add(Edge.of("e-clients", "clients", "api", values.get("clientLinkLatencyMs")).json());
	}

	// PostgreSQL, behind Redis on the cache stacks and behind Kafka on its own
	private static void backends(Experiment experiment, ObjectNode values, ArrayNode nodes, ArrayNode edges) {
		JsonNode dbLink = values.has("dbLinkLatencyMs") ? values.get("dbLinkLatencyMs") : PythonValues.number(0.0);
		if (DATABASE_STACKS.contains(experiment.stack())) {
			nodes.add(Nodes.database(values));
			edges.add(Edge.of("e-db", "api", "db", dbLink).json());
		}
		else if (experiment.hasCache()) {
			nodes.add(Nodes.cache(values, experiment.ttlS())).add(Nodes.database(values));
			edges.add(Edge.of("e-cache", "api", "cache", values.get("cacheLinkLatencyMs")).reads().json());
			edges.add(Edge.of("e-miss", "cache", "db", dbLink).fromPort("miss").reads().json());
		}
		else {
			nodes.add(Nodes.queue(values)).add(Nodes.database(values));
			edges.add(Edge.of("e-kafka", "api", "kafka", PythonValues.number(0.0)).json());
			edges.add(Edge.of("e-db", "kafka", "db", dbLink).json());
		}
	}

	private static ObjectNode clients(String label) {
		ObjectNode clients = NODES.objectNode().put("id", "clients").put("type", "traffic_source").put("label", label);
		clients.putObject("config").put("timeoutMs", TIMEOUT_MS).put("maxAttempts", 1);
		return clients;
	}

	private static ObjectNode workload(Experiment experiment, JsonNode rps, Window window) {
		ObjectNode workload = NODES.objectNode().set("rps", rps);
		return workload.put("durationS", window.warmup() + window.measured())
			.put("warmupS", window.warmup())
			.put("readRatio", experiment.readRatio())
			.put("keyCount", experiment.keys())
			.put("keySkew", experiment.skew())
			.put("seed", SEED)
			.put("arrivalProcess", "deterministic");
	}

	/**
	 * The simulated seconds of a load: warm-up, then measured.
	 *
	 * @param warmup the warm-up
	 * @param measured the measured seconds
	 */
	public record Window(int warmup, int measured) {

		/** A measured load step's. */
		public static final Window STEP = new Window(Protocol.WARMUP_S, Protocol.DURATION_S);

	}

}
