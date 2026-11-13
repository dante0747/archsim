package io.archsim.benchmarks.designs;

import java.util.List;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.ServiceRuntime;

/**
 * The nodes of a benchmark's design, each from the model inputs of its part.
 */
final class Nodes {

	/** The service's connection pool, which PostgreSQL's connections are. */
	static final int PG_POOL = 10;

	/**
	 * The service node's memory: the containers have no memory limit, and this one is
	 * large enough never to bind, so the model only reports the memory it predicts.
	 */
	static final int SERVICE_MEMORY_GIB = 1024;

	/**
	 * Redis's memory in the model: room for every key, as the real Redis has no limit.
	 */
	static final int CACHE_MEMORY_GIB = 4;

	/** Memory per cached key in the model: ArchSim's default, not calibrated. */
	static final double CACHE_ITEM_KIB = 1.0;

	private static final int QUEUE_CAPACITY = 100_000;

	private static final double DEFAULT_CPU_SHARE = 0.5;

	private static final int DATABASE_CORES = 2;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** A service node's memory settings, in the order the design writes them. */
	private static final List<String> MEMORY_KEYS = List.of("memoryBaselineMb", "memoryPerRpsMb", "memoryPerRequestMb");

	private Nodes() {
	}

	/**
	 * PostgreSQL behind the service's pool of connections.
	 * @param values the model inputs' values
	 * @return the node
	 */
	static ObjectNode database(ObjectNode values) {
		ObjectNode node = NODES.objectNode().put("id", "db").put("type", "database").put("label", "PostgreSQL");
		ObjectNode config = node.putObject("config")
			.put("technology", "postgresql")
			.put("maxConnections", PG_POOL)
			.put("queueCapacity", QUEUE_CAPACITY)
			.putNull("queueTimeoutMs")
			.put("cpuCores", DATABASE_CORES);
		config.set("cpuShare",
				values.has("dbCpuShare") ? values.get("dbCpuShare") : NODES.numberNode(DEFAULT_CPU_SHARE));
		copy(values, config, "readServiceTimeMs", "writeServiceTimeMs", "commitFlushMs");
		return node;
	}

	/**
	 * Redis with a TTL over the keyspace, filled on a miss.
	 * @param values the model inputs' values
	 * @param ttlSeconds the cache's TTL
	 * @return the node
	 */
	static ObjectNode cache(ObjectNode values, int ttlSeconds) {
		ObjectNode node = NODES.objectNode().put("id", "cache").put("type", "cache").put("label", "Redis");
		node.putObject("config")
			.put("technology", "redis")
			.put("hitModel", "ttl_keyspace")
			.put("ttlSeconds", ttlSeconds)
			.put("populateOnMiss", true)
			.set("serviceTimeMs", values.get("cacheServiceTimeMs"))
			.put("queueCapacity", QUEUE_CAPACITY)
			.put("cpuCores", 1)
			.put("memoryGib", CACHE_MEMORY_GIB)
			.put("itemSizeKb", CACHE_ITEM_KIB);
		return node;
	}

	/**
	 * Kafka: one broker and the real stack's partitions, read by one kafkajs consumer
	 * that polls in fetch cycles and processes its partitions a few at a time.
	 * @param values the model inputs' values
	 * @return the node
	 */
	static ObjectNode queue(ObjectNode values) {
		ObjectNode node = NODES.objectNode().put("id", "kafka").put("type", "message_queue").put("label", "Kafka");
		node.putObject("config")
			.put("technology", "kafka")
			.put("partitions", Designs.KAFKA_PARTITIONS)
			.put("consumers", 1)
			.set("consumerConcurrency", values.get("consumerConcurrency"))
			.set("fetchOverheadMs", values.get("fetchOverheadMs"))
			.put("brokers", 1)
			.putNull("maxDepth")
			// one broker: acks=all is the leader's write;
			// kafkajs sends each message at once
			.put("acks", "leader")
			.put("lingerMs", 0)
			.set("publishTimeMs", values.get("publishTimeMs"))
			.set("consumerTimeMs", values.get("consumerTimeMs"))
			.put("maxDeliveries", 1);
		return node;
	}

	/**
	 * A service on its runtime's profile: Node.js is one event loop on one CPU; Java, Go
	 * and Python are a pool of workers that each wait for their own PostgreSQL calls.
	 * Garbage collection and the service time by load apply to the service under test
	 * only, not to chain's API behind the gateway.
	 * @param values the model inputs' values
	 * @param service which service, and its runtime
	 * @return the node
	 */
	static ObjectNode service(ObjectNode values, Service service) {
		ServiceRuntime runtime = service.runtime();
		ObjectNode node = NODES.objectNode()
			.put("id", service.id())
			.put("type", "service")
			.put("label", service.label());
		ObjectNode config = node.putObject("config")
			.put("technology", runtime.technology())
			.put("platform", "docker")
			.put("replicas", 1);
		if (runtime.workers() == null) {
			eventLoop(values, service, config);
		}
		else {
			workers(values, service, config);
		}
		config.put("cpuCores", runtime.cpuCores()).put("memoryGib", SERVICE_MEMORY_GIB);
		memory(values, service.memory(), config);
		return node;
	}

	// Node.js: one process, its event loop; the GC and the load curve are the service
	// under test's only
	private static void eventLoop(ObjectNode values, Service service, ObjectNode config) {
		boolean underTest = service.timeKey().equals("serviceTimeMs");
		config.put("processesPerReplica", 1).put("queueCapacity", QUEUE_CAPACITY).putNull("queueTimeoutMs");
		config.set("serviceTimeMs", values.get(service.timeKey()));
		config.set("gcPauseMs", (underTest && values.has("gcPauseMs")) ? values.get("gcPauseMs") : NODES.numberNode(0));
		if (underTest) {
			copy(values, config, "gcEveryRequests", "serviceTimeByLoad");
		}
		config.put("warmupFactor", 1);
	}

	// Java, Go and Python: a pool of workers, each waiting for its own PostgreSQL calls
	private static void workers(ObjectNode values, Service service, ObjectNode config) {
		config.put("workersPerReplica", service.runtime().workers())
			.put("blockingDownstream", true)
			.put("queueCapacity", QUEUE_CAPACITY)
			.putNull("queueTimeoutMs");
		config.set("serviceTimeMs", values.get(service.timeKey()));
		copy(values, config, "serviceTimeByLoad");
		config.set("gcPauseMs", values.has("gcPauseMs") ? values.get("gcPauseMs") : NODES.numberNode(0));
		copy(values, config, "gcEveryRequests");
	}

	// the calibrated memory settings, or none (ArchSim's defaults) when not calibrated
	private static void memory(ObjectNode values, String memory, ObjectNode config) {
		if (!values.has(memory + "MemoryBaselineMb")) {
			return;
		}
		// each setting from its input: memoryBaselineMb from serviceMemoryBaselineMb
		for (String key : MEMORY_KEYS) {
			config.set(key, values.get(memory + Character.toUpperCase(key.charAt(0)) + key.substring(1)));
		}
	}

	private static void copy(ObjectNode values, ObjectNode config, String... keys) {
		for (String key : keys) {
			if (values.has(key)) {
				config.set(key, values.get(key));
			}
		}
	}

	/**
	 * A service of the design.
	 *
	 * @param id its node's id
	 * @param runtime its runtime
	 * @param timeKey the input of its service time
	 * @param label its node's label
	 * @param memory the prefix of its memory inputs
	 */
	record Service(String id, ServiceRuntime runtime, String timeKey, String label, String memory) {

	}

}
