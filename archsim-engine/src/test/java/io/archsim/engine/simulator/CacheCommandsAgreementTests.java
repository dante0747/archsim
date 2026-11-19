package io.archsim.engine.simulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The instant estimate against a run on a cache's commands (ADR-0092): each shard runs
 * its keys' commands on its own executor and cores, and a read-through cache fills a key
 * it missed with one more command, in the run and in the estimate alike. A Redis of 2
 * shards, each with one executor and 2 cores, takes 400 reads a second over evenly spread
 * keys, each a command of 0.5 ms; half miss, are read through from the database and
 * filled, so it runs 600 commands a second. They keep 15% of its executors and 7.5% of
 * its cores busy, where its reads over one shard's cores would make it 10% of each. The
 * design is simulated once, for every test.
 */
@TestInstance(Lifecycle.PER_CLASS)
class CacheCommandsAgreementTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** The cache's commands a second: its reads, and a fill for each that missed. */
	private static final double COMMANDS = 400 + 200;

	/** The share of its executors its commands keep busy. */
	private static final double EXECUTORS_BUSY = COMMANDS * 0.5 / 1000 / 2;

	/** The share of its cores its commands keep busy. */
	private static final double CORES_BUSY = COMMANDS * 0.5 / 1000 / (2 * 2);

	/** How far a share measured over the run may be from the estimated one. */
	private static final double UTILIZATION_POINTS = 0.01;

	private final JsonNode report = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped()).run(
			JSON.readTree("""
					{"schemaVersion": "1.10", "kind": "archsim.architecture", "metadata": {"name": "commands"},
					 "architecture": {"autoSize": false, "nodes": [{"id": "clients", "type": "traffic_source"},
					    {"id": "api", "type": "service", "config": {"workersPerReplica": 2000}},
					    {"id": "cache", "type": "cache", "config": {"technology": "redis", "shards": 2, "cpuCores": 2,
					      "hitRatio": 0.5, "populateOnMiss": true, "queueCapacity": 5000,
					      "serviceTimeMs": {"kind": "constant", "value": 0.5}}},
					    {"id": "db", "type": "database", "config": {"maxConnections": 400}}],
					  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
					    {"id": "api-cache", "source": "api", "target": "cache"},
					    {"id": "cache-db", "source": "cache", "sourcePort": "miss", "target": "db",
					     "config": {"requestTypes": ["read"]}}]},
					 "workload": {"rps": 400, "readRatio": 1, "keySkew": 0, "durationS": 60, "warmupS": 5}}"""),
			RunRequest.of(null));

	@Test
	void run_aShardedReadThroughCache_estimatesItsCommandsShareOfEveryShardsCores() {
		assertThat(estimated().required("cpuUtilization").doubleValue()).isEqualTo(CORES_BUSY);
	}

	@Test
	void run_aShardedReadThroughCache_keepsItsCoresAsBusyAsEstimated() {
		JsonNode simulated = ReferenceDesigns.summaryOf(this.report, "cache");

		assertThat(simulated.required("cpuCoreUtilization").doubleValue())
			.isCloseTo(estimated().required("cpuUtilization").doubleValue(), within(UTILIZATION_POINTS));
	}

	@Test
	void run_aShardedReadThroughCache_estimatesItsCommandsShareOfItsExecutors() {
		assertThat(estimated().required("slotUtilization").doubleValue()).isEqualTo(EXECUTORS_BUSY);
	}

	@Test
	void run_aShardedReadThroughCache_keepsItsExecutorsAsBusyAsEstimated() {
		JsonNode simulated = ReferenceDesigns.summaryOf(this.report, "cache");

		assertThat(simulated.required("utilization").doubleValue())
			.isCloseTo(estimated().required("slotUtilization").doubleValue(), within(UTILIZATION_POINTS));
	}

	// the cache's part in the estimate the report records
	private JsonNode estimated() {
		return this.report.at("/estimate/parts")
			.valueStream()
			.filter((part) -> "cache".equals(part.path("id").stringValue()))
			.findFirst()
			.orElseThrow();
	}

}
