package io.archsim.benchmarks.inputs;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.designs.Simulations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The second held-out set's inputs and designs (ADR-0090) from two calibration passes
 * ({@link HeldOutDesigns}): each service's CPUs and workers, the connections PostgreSQL
 * serves at once, the traffic's keys and each stack's links. They are sealed, so none has
 * a kept calibration to read.
 */
class SecondSetDesignsTests {

	// python-wide's container has four CPUs where its runtime has two; the workers,
	// Tomcat's threads and Go's goroutines stay their runtime's
	@ParameterizedTest
	@CsvSource({ "python-cores, python, 2, 10", "python-wide, python, 4, 10", "java-hot-row, java, 2, 200",
			"go-pool, go, 2, 4096" })
	void design_aJavaGoOrPythonService_hasItsExperimentsCpusAndItsRuntimesWorkers(String experiment, String technology,
			int cpus, int workers) {
		JsonNode api = HeldOutDesigns.node(HeldOutDesigns.design(experiment), "api");

		assertThat(List.of(api.get("technology").stringValue(), api.get("cpuCores").intValue(),
				api.get("workersPerReplica").intValue(), api.get("blockingDownstream").booleanValue()))
			.containsExactly(technology, cpus, workers, true);
	}

	@Test
	void design_cacheMiss_isOneEventLoopOnOneCpu() {
		JsonNode api = HeldOutDesigns.node(HeldOutDesigns.design("cache-miss"), "api");

		assertThat(List.of(api.get("technology").stringValue(), api.get("processesPerReplica").intValue(),
				api.get("cpuCores").intValue()))
			.containsExactly("nodejs", 1, 1);
	}

	// go-pool's service holds two connections, every other one ten
	@ParameterizedTest
	@CsvSource({ "python-cores, 10", "python-wide, 10", "java-hot-row, 10", "go-pool, 2", "cache-miss, 10" })
	void design_anExperimentOfTheSecondSet_givesPostgresqlItsServicesPool(String experiment, int connections) {
		JsonNode database = HeldOutDesigns.node(HeldOutDesigns.design(experiment), "db");

		assertThat(database.get("maxConnections").intValue()).isEqualTo(connections);
	}

	// uniform keys, every one: java-hot-row's one row, cache-miss's million
	@ParameterizedTest
	@CsvSource({ "python-cores, 100000, 0.9", "python-wide, 100000, 0.9", "java-hot-row, 1, 0.0",
			"go-pool, 100000, 0.5", "cache-miss, 1000000, 1.0" })
	void design_anExperimentOfTheSecondSet_hasItsTrafficsKeysSkewAndMix(String experiment, int keys, double reads) {
		JsonNode workload = HeldOutDesigns.design(experiment).get("workload");

		assertThat(List.of(workload.get("keyCount").intValue(), workload.get("keySkew").doubleValue(),
				workload.get("readRatio").doubleValue()))
			.containsExactly(keys, 0.0, reads);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|',
			value = { "python-cores | clients>api api>db", "python-wide | clients>api api>db",
					"java-hot-row | clients>api api>db", "go-pool | clients>api api>db",
					"cache-miss | clients>api api>cache cache>db" })
	void design_anExperimentOfTheSecondSet_linksItsStacksNodesInOrder(String experiment, String links) {
		JsonNode edges = HeldOutDesigns.design(experiment).at("/architecture/edges");

		assertThat(String.join(" ",
				edges.valueStream()
					.map((edge) -> edge.get("source").stringValue() + ">" + edge.get("target").stringValue())
					.toList()))
			.isEqualTo(links);
	}

	@Test
	void design_cacheMiss_expiresItsKeysAfterFiveSeconds() {
		assertThat(HeldOutDesigns.node(HeldOutDesigns.design("cache-miss"), "cache").get("ttlSeconds").intValue())
			.isEqualTo(5);
	}

	// only writes reach PostgreSQL, so the model times no read
	@Test
	void of_javaHotRow_timesItsWritesAndTheirFlushAndNoRead() {
		ObjectNode inputs = HeldOutDesigns.inputs("java-hot-row");

		assertThat(inputs.propertyNames()).contains("writeServiceTimeMs", "commitFlushMs")
			.doesNotContain("readServiceTimeMs");
	}

	// the commit's wait, 2.0 - 0.8 ms, less what the model's writes queue at 400 req/s,
	// half of them writes
	@Test
	void of_goPool_fitsAFlushShorterThanTheCommitsWait() {
		double flush = HeldOutDesigns.inputs("go-pool").at("/commitFlushMs/value/median").doubleValue();

		assertThat(flush).isCloseTo(1.2, within(0.05 * 1.2)).isLessThan(1.2);
	}

	// Redis's own time from the service's /stats, as on cache-ttl
	@Test
	void of_cacheMiss_takesRedissOwnTimeFromTheServicesStats() {
		assertThat(HeldOutDesigns.inputs("cache-miss").at("/cacheServiceTimeMs/value/mean").doubleValue())
			.isEqualTo(0.003);
	}

	@ParameterizedTest
	@ValueSource(strings = { "python-cores", "python-wide", "java-hot-row", "go-pool", "cache-miss" })
	void simulate_aDesignOfTheSecondSet_servesEveryRequestOnABusyService(String experiment) {
		JsonNode simulated = Simulations.onTheEngine().simulate(HeldOutDesigns.design(experiment));

		assertThat(simulated.get("successRate").doubleValue()).isEqualTo(1.0);
		assertThat(simulated.at("/resources/api/cores").doubleValue()).isPositive();
		assertThat(simulated.at("/resources/db/cores").doubleValue()).isPositive();
	}

	// a million keys drawn uniformly, each kept 5 s: at 100 req/s hardly one is found
	@Test
	void simulate_cacheMiss_missesNearlyEveryLookup() {
		JsonNode simulated = Simulations.onTheEngine().simulate(HeldOutDesigns.design("cache-miss"));

		assertThat(simulated.get("cacheHitRatio").doubleValue()).isLessThan(0.01);
	}

}
