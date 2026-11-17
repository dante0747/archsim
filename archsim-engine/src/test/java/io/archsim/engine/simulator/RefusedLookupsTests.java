package io.archsim.engine.simulator;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A cache counts each read it refuses (ADR-0082), down or turned away by its queue, once
 * however often its caller retries it and not at all when it answers it on a later
 * attempt; and nothing else it refuses: not a write's invalidation, not a write-back, and
 * not a read its data store refused behind it.
 */
class RefusedLookupsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Down for the whole run. */
	private static final String CACHE_DOWN = """
			[{"id": "down", "kind": "outage", "target": "cache", "startS": 0, "durationS": 6}]""";

	/** A cache that sends no write-back after a miss. */
	private static final String NO_WRITE_BACK = ", \"populateOnMiss\": false";

	/**
	 * Refusals at the window's edges whose retry or write-back falls on the other side:
	 * at most the reads of one 50-ms backoff at 100 req/s, 5, and a few more for chance.
	 */
	private static final long EDGES = 8;

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void run_aCacheDownForTheWholeRunThatOnlyGetsLookups_countsEveryRefusalAsALookup() {
		JsonNode cache = cache(run(cacheAside(1.0, NO_WRITE_BACK, "", CACHE_DOWN)));

		assertThat(cache.at("/counters/refusedLookups").asLong()).isPositive()
			.isEqualTo(cache.path("rejectedTotal").asLong());
	}

	@Test
	void run_aCacheDownThatGetsAWriteBackAfterEachRefusedLookup_countsOnlyTheLookups() {
		JsonNode cache = cache(run(cacheAside(1.0, ", \"populateOnMiss\": true", "", CACHE_DOWN)));

		assertThat(cache.at("/counters/refusedLookups").asLong()).isPositive()
			.isCloseTo(cache.path("rejectedTotal").asLong() / 2, within(EDGES));
	}

	@Test
	void run_aCacheDownThatOnlyGetsWrites_countsNoLookup() {
		JsonNode cache = cache(run(cacheAside(0.0, "", "", CACHE_DOWN)));

		assertThat(cache.path("rejectedTotal").asLong()).isPositive();
		assertThat(cache.path("counters").has("refusedLookups")).isFalse();
	}

	@Test
	void run_aLinkThatRetriesALookupTheCacheRefused_countsTheReadOnce() {
		JsonNode cache = cache(run(cacheAside(1.0, NO_WRITE_BACK, ", \"maxAttempts\": 2", CACHE_DOWN)));

		assertThat(cache.at("/counters/refusedLookups").asLong()).isPositive()
			.isCloseTo(cache.path("rejectedTotal").asLong() / 2, within(EDGES));
	}

	@Test
	void run_aLookupTheCacheAnswersOnARetry_countsAsTheHitOrMissItFound() {
		JsonNode cache = cache(run(cacheAside(1.0, NO_WRITE_BACK, ", \"maxAttempts\": 2, \"retryBackoffMs\": 1000", """
				[{"id": "down", "kind": "outage", "target": "cache", "startS": 2, "durationS": 0.5}]""")));

		long answered = cache.at("/counters/hits").asLong() + cache.at("/counters/misses").asLong();

		assertThat(cache.path("rejectedTotal").asLong()).isPositive();
		assertThat(cache.path("counters").has("refusedLookups")).isFalse();
		assertThat(answered).isCloseTo(cache.path("arrivals").asLong() - cache.path("rejectedTotal").asLong(),
				within(EDGES));
	}

	@Test
	void run_aCacheWhoseQueueTurnsLookupsAway_countsThemAsRefused() {
		JsonNode cache = cache(run(cacheAside(1.0,
				NO_WRITE_BACK + ", \"serviceTimeMs\": {\"kind\": \"constant\", \"value\": 20}, \"queueTimeoutMs\": 5",
				"", "[]")));

		assertThat(cache.path("rejectedTotal").asLong()).isPositive();
		assertThat(cache.at("/counters/refusedLookups").asLong()).isEqualTo(cache.path("rejectedTotal").asLong());
	}

	@Test
	void run_aReadThroughCacheWhoseStoreIsDown_countsNoLookupAsRefused() {
		JsonNode cache = cache(run("""
				{"schemaVersion": "1.10", "kind": "archsim.architecture", "metadata": {"name": "read-through"},
				 "architecture": {"autoSize": false, "nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service"},
				    {"id": "cache", "type": "cache", "config": {"hitRatio": 0.5}},
				    {"id": "db", "type": "database"}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				    {"id": "api-cache", "source": "api", "target": "cache"},
				    {"id": "cache-db", "source": "cache", "sourcePort": "miss", "target": "db",
				     "config": {"requestTypes": ["read"]}}]},
				 "workload": {"rps": 100, "readRatio": 1.0, "durationS": 6, "warmupS": 1,
				  "faults": [{"id": "down", "kind": "outage", "target": "db", "startS": 0, "durationS": 6}]}}"""));

		assertThat(cache.at("/counters/misses").asLong()).isPositive();
		assertThat(cache.path("counters").has("refusedLookups")).isFalse();
	}

	private JsonNode run(String design) {
		return this.simulator.run(JSON.readTree(design), RunRequest.of(null));
	}

	private static JsonNode cache(JsonNode report) {
		return ReferenceDesigns.summaryOf(report, "cache");
	}

	/**
	 * A service reading through a cache-aside cache and carrying on when a lookup fails.
	 * @param readRatio the share of requests that read
	 * @param cacheSettings more of the cache's settings, each after a comma
	 * @param linkSettings more of the settings of the service's link to the cache, each
	 * after a comma
	 * @param faults the run's faults
	 * @return the design
	 */
	private static String cacheAside(double readRatio, String cacheSettings, String linkSettings, String faults) {
		return """
				{"schemaVersion": "1.10", "kind": "archsim.architecture", "metadata": {"name": "cache-aside"},
				 "architecture": {"autoSize": false, "nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"workersPerReplica": 64}},
				    {"id": "cache", "type": "cache", "config": {"hitRatio": 0.5%s}},
				    {"id": "db", "type": "database"}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				    {"id": "api-cache", "source": "api", "target": "cache",
				     "config": {"order": 0, "continueOnFailure": true%s}},
				    {"id": "api-db", "source": "api", "target": "db", "config": {"order": 1, "readsOnMiss": true}}]},
				 "workload": {"rps": 100, "readRatio": %s, "durationS": 6, "warmupS": 1, "faults": %s}}"""
			.formatted(cacheSettings, linkSettings, readRatio, faults);
	}

}
