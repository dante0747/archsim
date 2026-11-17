package io.archsim.engine.simulator;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The starter losing its Redis cache for a while, as the editor's "Lose Redis for 15 s"
 * pad plans it: the lookups the cache refused while it was down count against its hit
 * ratio, its finding says what became of them, and the advice after the outage asks only
 * for what the design does not do yet (ADR-0082). Shortened to 30 s after 5 s of warm-up,
 * the cache down from 12 to 22 s.
 */
@TestInstance(Lifecycle.PER_CLASS)
class CacheOutageTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final double DIGITS = 1e-4;

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	/**
	 * The starter as drawn: its API carries on when the cache fails, and reads the
	 * database on a miss.
	 */
	private final JsonNode fallingBack = simulate(true, true);

	/** The same, its API failing a read when the cache does. */
	private final JsonNode failingWithTheCache = simulate(false, true);

	@Test
	void run_aCacheThatWasDown_countsTheLookupsItRefusedInItsHitRatio() {
		JsonNode cache = ReferenceDesigns.summaryOf(this.fallingBack, "cache");
		double hits = cache.at("/counters/hits").doubleValue();
		double misses = cache.at("/counters/misses").doubleValue();
		double refused = cache.at("/counters/refusedLookups").doubleValue();

		assertThat(refused).isPositive();
		assertThat(cache.required("hitRatio").doubleValue()).isCloseTo(hits / (hits + misses + refused),
				within(DIGITS));
	}

	@Test
	void run_aCacheThatWasDown_explainsItsHitRatioWithTheLookupsItRefused() {
		JsonNode cache = ReferenceDesigns.summaryOf(this.fallingBack, "cache");
		JsonNode formula = node(this.fallingBack, "cache").required("explain")
			.valueStream()
			.filter((explained) -> explained.required("id").stringValue().equals("hit_ratio"))
			.findFirst()
			.orElseThrow();

		assertThat(formula.required("formula").stringValue())
			.isEqualTo("hit ratio = hits ÷ (hits + misses + refused lookups)");
		assertThat(formula.required("substituted").stringValue()).isEqualTo(String.format(Locale.ROOT,
				"%,d ÷ (%,d + %,d + %,d)", cache.at("/counters/hits").asLong(), cache.at("/counters/hits").asLong(),
				cache.at("/counters/misses").asLong(), cache.at("/counters/refusedLookups").asLong()));
		assertThat(formula.required("result").doubleValue()).isCloseTo(cache.required("hitRatio").doubleValue(),
				within(DIGITS));
	}

	@Test
	void run_aCacheWhoseCallerFallsBack_saysItReadTheLookupsTheCacheRefusedFromTheDataStore() {
		String detail = finding(this.fallingBack, "cache").required("detail").stringValue();

		assertThat(detail).contains("API Servers read them from PostgreSQL; it refused ")
			.endsWith(" reads/s, which API Servers read from PostgreSQL too.");
	}

	@Test
	void run_aCacheWhoseCallerFailsWithIt_saysTheLookupsItRefusedFailed() {
		String detail = finding(this.failingWithTheCache, "cache").required("detail").stringValue();

		assertThat(detail).endsWith(" reads/s, which failed.");
	}

	@Test
	void run_aCacheThatWasUp_leavesRefusedLookupsOutOfItsFinding() {
		JsonNode finding = finding(simulate(true, false), "cache");

		assertThat(finding.required("evidence").has("refusedLookupRps")).isFalse();
		assertThat(finding.required("detail").stringValue()).doesNotContain("refused");
	}

	@Test
	void run_aCacheItsCallersAlreadyFallBackFrom_isNotAdvisedToFallBack() {
		String suggestion = finding(this.fallingBack, "fault_impact").required("suggestion").stringValue();

		assertThat(suggestion).doesNotContain("turn on")
			.doesNotContain("give ")
			.startsWith("Its caller, API Servers, already treats a failed lookup on Redis Cache as a miss");
	}

	@Test
	void run_aCacheWhoseCallerFailsItsReads_isAdvisedOnlyToCarryOn() {
		String suggestion = finding(this.failingWithTheCache, "fault_impact").required("suggestion").stringValue();

		assertThat(suggestion).contains("turn on 'Carry on if this call fails' on API Servers → Redis Cache")
			.doesNotContain("'Reads only on a cache miss'");
	}

	@Test
	void run_aReadThroughCacheThatWasDown_saysTheLookupsItRefusedFailed() {
		JsonNode report = this.simulator.run(JSON.readTree("""
				{"schemaVersion": "1.9", "kind": "archsim.architecture", "metadata": {"name": "read-through"},
				 "architecture": {"autoSize": false, "nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "label": "API", "config": {"workersPerReplica": 64}},
				    {"id": "cache", "type": "cache", "label": "Cache", "config": {"hitRatio": 0.5}},
				    {"id": "db", "type": "database", "label": "DB"}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				    {"id": "api-cache", "source": "api", "target": "cache"},
				    {"id": "cache-db", "source": "cache", "sourcePort": "miss", "target": "db",
				     "config": {"requestTypes": ["read"]}}]},
				 "workload": {"rps": 100, "readRatio": 1.0, "durationS": 8, "warmupS": 1,
				  "faults": [{"id": "down", "kind": "outage", "target": "cache", "startS": 3, "durationS": 2}]}}"""),
				RunRequest.of(null));

		String detail = finding(report, "cache").required("detail").stringValue();

		assertThat(detail).contains(" reads/s continued to DB; it refused ").endsWith(" reads/s, which failed.");
	}

	@Test
	void run_aCacheWithCallersOfBothKinds_saysTheLookupsItRefusedWereReadBySomeAndFailedForTheRest() {
		JsonNode report = this.simulator.run(JSON.readTree("""
				{"schemaVersion": "1.9", "kind": "archsim.architecture", "metadata": {"name": "two callers"},
				 "architecture": {"autoSize": false, "nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "jobs", "type": "traffic_source"},
				    {"id": "api", "type": "service", "label": "API", "config": {"workersPerReplica": 64}},
				    {"id": "worker", "type": "service", "label": "Worker", "config": {"workersPerReplica": 64}},
				    {"id": "cache", "type": "cache", "label": "Cache", "config": {"hitRatio": 0.5}},
				    {"id": "db", "type": "database", "label": "DB"}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				    {"id": "jobs-worker", "source": "jobs", "target": "worker"},
				    {"id": "api-cache", "source": "api", "target": "cache",
				     "config": {"order": 0, "continueOnFailure": true}},
				    {"id": "api-db", "source": "api", "target": "db", "config": {"order": 1, "readsOnMiss": true}},
				    {"id": "worker-cache", "source": "worker", "target": "cache", "config": {"order": 0}},
				    {"id": "worker-db", "source": "worker", "target": "db",
				     "config": {"order": 1, "readsOnMiss": true}}]},
				 "workload": {"rps": 100, "readRatio": 1.0, "durationS": 8, "warmupS": 1,
				  "faults": [{"id": "down", "kind": "outage", "target": "cache", "startS": 3, "durationS": 2}]}}"""),
				RunRequest.of(null));

		String detail = finding(report, "cache").required("detail").stringValue();

		assertThat(detail).contains("; it refused ")
			.endsWith(" reads/s, which the callers that carry on read from the data store, and the rest failed.");
	}

	@Test
	void run_aCacheWhoseCallersAllFallBack_namesEachThatReadTheLookupsItRefused() {
		JsonNode report = this.simulator.run(JSON.readTree("""
				{"schemaVersion": "1.9", "kind": "archsim.architecture", "metadata": {"name": "two callers"},
				 "architecture": {"autoSize": false, "nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "jobs", "type": "traffic_source"},
				    {"id": "api", "type": "service", "label": "API", "config": {"workersPerReplica": 64}},
				    {"id": "worker", "type": "service", "label": "Worker", "config": {"workersPerReplica": 64}},
				    {"id": "cache", "type": "cache", "label": "Cache", "config": {"hitRatio": 0.5}},
				    {"id": "db", "type": "database", "label": "DB"}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				    {"id": "jobs-worker", "source": "jobs", "target": "worker"},
				    {"id": "api-cache", "source": "api", "target": "cache",
				     "config": {"order": 0, "continueOnFailure": true}},
				    {"id": "api-db", "source": "api", "target": "db", "config": {"order": 1, "readsOnMiss": true}},
				    {"id": "worker-cache", "source": "worker", "target": "cache",
				     "config": {"order": 0, "continueOnFailure": true}},
				    {"id": "worker-db", "source": "worker", "target": "db",
				     "config": {"order": 1, "readsOnMiss": true}}]},
				 "workload": {"rps": 100, "readRatio": 1.0, "durationS": 8, "warmupS": 1,
				  "faults": [{"id": "down", "kind": "outage", "target": "cache", "startS": 3, "durationS": 2}]}}"""),
				RunRequest.of(null));

		String detail = finding(report, "cache").required("detail").stringValue();

		assertThat(detail).contains("; it refused ").endsWith(" reads/s, which API and Worker read from DB too.");
	}

	private JsonNode simulate(boolean carriesOn, boolean down) {
		ObjectNode starter = ReferenceDesigns.example("cache-aside-starter.json");
		((ObjectNode) starter.required("workload")).put("durationS", 30).put("warmupS", 5);
		if (down) {
			((ObjectNode) starter.required("workload")).putArray("faults")
				.addObject()
				.put("id", "lose-cache")
				.put("kind", "outage")
				.put("target", "cache")
				.put("startS", 12)
				.put("durationS", 10);
		}
		starter.required("architecture")
			.required("edges")
			.valueStream()
			.filter((link) -> link.required("id").stringValue().equals("api-cache"))
			.forEach((link) -> ((ObjectNode) link.required("config")).put("continueOnFailure", carriesOn));
		return this.simulator.run(starter, RunRequest.of(null));
	}

	private static JsonNode node(JsonNode report, String id) {
		return report.required("nodes")
			.valueStream()
			.filter((node) -> node.required("id").stringValue().equals(id))
			.findFirst()
			.orElseThrow();
	}

	private static JsonNode finding(JsonNode report, String kind) {
		return report.required("findings")
			.valueStream()
			.filter((finding) -> finding.required("kind").stringValue().equals(kind))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no " + kind + " finding in " + report.required("findings")));
	}

}
