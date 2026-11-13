package io.archsim.benchmarks.designs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.values.PythonValues;

/**
 * PostgreSQL alone: a pass's queries sent at its rate over the service-to-database link,
 * to fit the WAL flush with which the model's writes wait as the service's did.
 */
public final class DatabaseAlone {

	private static final int WARMUP_S = 2;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final Simulations simulations;

	/**
	 * PostgreSQL alone, simulated on an engine.
	 * @param simulations the simulations
	 */
	public DatabaseAlone(Simulations simulations) {
		this.simulations = simulations;
	}

	/**
	 * The model's database at a pass's rate and mix.
	 * @param experiment the experiment
	 * @param values the database's inputs' values
	 * @param rate the pass's rate
	 * @param mix the share of reads in its mix, and the simulated seconds after a 2 s
	 * warm-up
	 * @return its commits per flush, mean lock wait and mean commit wait
	 */
	public JsonNode simulate(Experiment experiment, ObjectNode values, JsonNode rate, Mix mix) {
		return this.simulations.simulate(design(experiment, values, rate, mix)).get("database");
	}

	static ObjectNode design(Experiment experiment, ObjectNode values, JsonNode rate, Mix mix) {
		ObjectNode design = NODES.objectNode().put("schemaVersion", "1.0").put("kind", "archsim.architecture");
		design.putObject("metadata").put("name", "benchmark " + experiment.id() + ": PostgreSQL alone");
		ObjectNode architecture = design.putObject("architecture");
		ArrayNode nodes = architecture.putArray("nodes");
		ObjectNode clients = nodes.addObject()
			.put("id", "clients")
			.put("type", "traffic_source")
			.put("label", "service");
		clients.putObject("config").put("timeoutMs", Designs.TIMEOUT_MS).put("maxAttempts", 1);
		nodes.add(Nodes.database(values));
		JsonNode link = values.has("dbLinkLatencyMs") ? values.get("dbLinkLatencyMs") : PythonValues.number(0.0);
		architecture.putArray("edges").add(Edge.of("e-db", "clients", "db", link).json());
		architecture.put("autoSize", false);
		ObjectNode workload = design.putObject("workload").set("rps", rate);
		workload.put("durationS", WARMUP_S + mix.seconds())
			.put("warmupS", WARMUP_S)
			.put("readRatio", mix.readRatio())
			.put("keyCount", experiment.keys())
			.put("keySkew", experiment.skew())
			.put("seed", Designs.SEED)
			.put("arrivalProcess", "deterministic");
		design.putArray("scenarios");
		return design;
	}

	/**
	 * The pass's mix and the simulated seconds.
	 *
	 * @param readRatio the share of reads
	 * @param seconds the simulated seconds after the warm-up
	 */
	public record Mix(double readRatio, double seconds) {

	}

}
