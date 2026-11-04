package io.archsim.engine.load;

import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The load each part carries, estimated without a run. */
class LoadEstimatorTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final LoadEstimator estimator = new LoadEstimator(StandardComponents.registry());

	@Test
	void estimate_twoClients_shareTheWorkloadByTheirTrafficShares() {
		ArchitectureDocument design = design("""
				[{"id": "web", "type": "web_client", "config": {"trafficShare": 75}},
				 {"id": "app", "type": "mobile_app", "config": {"trafficShare": 25}}]""", "[]");

		LoadEstimate loads = estimate(design, CacheMisses.UNKNOWN);

		assertLoad(loads, "web", 60, 15);
		assertLoad(loads, "app", 20, 5);
	}

	@Test
	void estimate_aLoadBalancer_splitsByTheReplicasBehindIt() {
		ArchitectureDocument design = design("""
				[{"id": "clients", "type": "traffic_source"}, {"id": "lb", "type": "load_balancer"},
				 {"id": "one", "type": "service", "config": {"replicas": 1}},
				 {"id": "three", "type": "service", "config": {"replicas": 3}}]""",
				"[%s, %s, %s]".formatted(link("clients", "lb"), link("lb", "one"), link("lb", "three")));

		LoadEstimate loads = estimate(design, CacheMisses.UNKNOWN);

		assertLoad(loads, "one", 20, 5);
		assertLoad(loads, "three", 60, 15);
	}

	@Test
	void estimate_cacheAside_writesBackEachMissAndFetchesItFromTheDatabase() {
		ArchitectureDocument design = cacheAside("{\"hitRatio\": 0.75}");

		LoadEstimate loads = estimate(design, CacheMisses.UNKNOWN);

		assertLoad(loads, "cache", 100, 20);
		assertLoad(loads, "db", 20, 20);
	}

	@Test
	void estimate_aCacheThatLeavesItsHitRatioOut_missesByItsDefault() {
		ArchitectureDocument design = cacheAside("{}");

		LoadEstimate loads = estimate(design, CacheMisses.UNKNOWN);

		assertLoad(loads, "db", 16, 20);
	}

	@Test
	void estimate_twoClientsWithOneId_sendTheLaterClientsShareTwice() {
		ArchitectureDocument design = design("""
				[{"id": "clients", "type": "traffic_source"}, {"id": "clients", "type": "traffic_source"},
				 {"id": "api", "type": "service"}]""", "[%s]".formatted(link("clients", "api")));

		LoadEstimate loads = estimate(design, CacheMisses.UNKNOWN);

		assertLoad(loads, "clients", 40, 10);
		assertLoad(loads, "api", 80, 20);
	}

	@Test
	void estimate_partsOnACycle_keepWhatReachedThem() {
		ArchitectureDocument design = design("""
				[{"id": "clients", "type": "traffic_source"}, {"id": "a", "type": "service"},
				 {"id": "b", "type": "service"}]""",
				"[%s, %s, %s]".formatted(link("clients", "a"), link("a", "b"), link("b", "a")));

		LoadEstimate loads = estimate(design, CacheMisses.UNKNOWN);

		assertLoad(loads, "a", 80, 20);
		assertLoad(loads, "b", 0, 0);
		assertThat(loads.edges()).containsOnlyKeys("clients-a");
	}

	@Test
	void estimate_aTtlCacheWhoseMissesDependOnTheRun_hasNoLoadDownstream() {
		ArchitectureDocument design = cacheAside("{\"hitModel\": \"ttl_keyspace\"}");

		LoadEstimate loads = estimate(design, CacheMisses.UNKNOWN);

		assertLoad(loads, "cache", 80, 20);
		assertThat(loads.node("db")).isEmpty();
		assertThat(loads.edges()).containsEntry("api-db", null);
	}

	@Test
	void estimate_aTtlCacheWithAnEstimatedShare_passesThatShareOn() {
		ArchitectureDocument design = cacheAside("{\"hitModel\": \"ttl_keyspace\"}");

		LoadEstimate loads = estimate(design, new CacheMisses(Map.of("cache", 0.5), null));

		assertLoad(loads, "db", 40, 20);
	}

	@Test
	void estimate_aSettingOfTheWrongType_countsAsMissing() {
		ArchitectureDocument design = cacheAside("{\"hitRatio\": \"high\"}");

		LoadEstimate loads = estimate(design, CacheMisses.UNKNOWN);

		assertLoad(loads, "db", 80, 20);
	}

	@Test
	void ttlMissShares_aTtlCache_isEstimatedFromItsTraffic() {
		ArchitectureDocument design = cacheAside("{\"hitModel\": \"ttl_keyspace\", \"ttlSeconds\": 60}");

		Map<String, Double> shares = this.estimator.ttlMissShares(design.architecture(), design.workload(), 100);

		assertThat(shares).containsOnlyKeys("cache");
		assertThat(shares.get("cache")).isCloseTo(0.5471327822344779, within(1e-12));
	}

	private static void assertLoad(LoadEstimate loads, String id, double read, double write) {
		NodeLoad load = loads.node(id).orElseThrow();
		assertThat(load.read()).as("%s reads", id).isCloseTo(read, within(1e-9));
		assertThat(load.write()).as("%s writes", id).isCloseTo(write, within(1e-9));
	}

	private LoadEstimate estimate(ArchitectureDocument design, CacheMisses misses) {
		return this.estimator.estimate(design.architecture(), design.workload(), 100, misses);
	}

	/**
	 * Clients read through a service that looks up a cache first and reads the database
	 * on a miss.
	 */
	private static ArchitectureDocument cacheAside(String cacheConfig) {
		return design("""
				[{"id": "clients", "type": "traffic_source"}, {"id": "api", "type": "service"},
				 {"id": "cache", "type": "cache", "config": %s}, {"id": "db", "type": "database"}]"""
			.formatted(cacheConfig), """
					[{"id": "clients-api", "source": "clients", "target": "api"},
					 {"id": "api-cache", "source": "api", "target": "cache"},
					 {"id": "api-db", "source": "api", "target": "db", "config": {"readsOnMiss": true}}]""");
	}

	private static String link(String source, String target) {
		return "{\"id\": \"%s-%s\", \"source\": \"%s\", \"target\": \"%s\"}".formatted(source, target, source, target);
	}

	private static ArchitectureDocument design(String nodes, String edges) {
		return DesignDocuments.parse(JSON.readTree("""
				{"architecture": {"nodes": %s, "edges": %s},
				 "workload": {"rps": 100, "readRatio": 0.8, "durationS": 60}}""".formatted(nodes, edges)));
	}

}
