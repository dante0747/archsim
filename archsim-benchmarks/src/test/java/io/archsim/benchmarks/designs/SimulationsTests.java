package io.archsim.benchmarks.designs;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.engine.calibration.resources.MemoryFits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Designs simulated on the engine and what the harness reads from them: each node's busy
 * cores and memory, the database's figures, and that memory and the load curve move the
 * resources the model predicts as they should.
 */
class SimulationsTests {

	private static final Experiment PG_MIXED = Experiments.standard().get("pg-mixed");

	private static final Simulations SIMULATIONS = Simulations.onTheEngine();

	private static final Designs.Window SHORT = new Designs.Window(2, 5);

	private static final List<String> LATENCY = List.of("p50", "p95", "p99", "mean", "successRate", "throughputRps");

	@Test
	void resources_eachNode_isItsBusyCoresAndItsMemory() {
		ObjectNode design = JsonText.object("""
				{"architecture": {"nodes": [
				  {"id": "api", "config": {"cpuCores": 1, "memoryGib": 1024}},
				  {"id": "db", "config": {"cpuCores": 2}},
				  {"id": "cache", "config": {"cpuCores": 1, "itemSizeKb": 1.0}},
				  {"id": "kafka", "config": {}}]}}""");
		ObjectNode report = JsonText.object("""
				{"nodes": [
				  {"id": "api", "summary": {"cpuCoreUtilization": 0.5, "memoryPeakGiB": 0.25}},
				  {"id": "db", "summary": {"cpuCoreUtilization": 0.1}},
				  {"id": "cache", "summary": {"cpuCoreUtilization": 0.02, "keysCached": 2048}},
				  {"id": "kafka", "summary": {}}]}""");

		ObjectNode resources = Simulations.resources(design, report);

		assertThat(resources).isEqualTo(JsonText.read("""
				{"api": {"cores": 0.5, "memoryMiB": 256.0}, "db": {"cores": 0.2},
				 "cache": {"cores": 0.02, "memoryMiB": 2.0}}"""));
	}

	@Test
	void cacheHitRatio_aCacheThatRefusedNoLookup_isItsReportedHitRatio() {
		JsonNode cache = JsonText.read("{\"hitRatio\": 0.8, \"counters\": {\"hits\": 80, \"misses\": 21}}");

		assertThat(Simulations.cacheHitRatio(cache)).isEqualTo(JsonText.read("0.8"));
	}

	@Test
	void cacheHitRatio_aCacheThatRefusedLookups_isItsHitsOverTheLookupsItAnswered() {
		JsonNode cache = JsonText
			.read("{\"hitRatio\": 0.5, \"counters\": {\"hits\": 50, \"misses\": 25, \"refusedLookups\": 25}}");

		assertThat(Simulations.cacheHitRatio(cache)).isEqualTo(JsonText.read("0.6667"));
	}

	@Test
	void cacheHitRatio_aCacheThatRefusedEveryLookup_isNone() {
		JsonNode cache = JsonText.read("{\"hitRatio\": 0.0, \"counters\": {\"refusedLookups\": 25}}");

		assertThat(Simulations.cacheHitRatio(cache).isNull()).isTrue();
	}

	@Test
	void simulate_aDesignWithPostgresql_reportsWhatTheDatabaseDid() {
		ObjectNode simulated = SIMULATIONS
			.simulate(Designs.design(PG_MIXED, latestInputs(), JsonText.read("500"), SHORT));

		assertThat(simulated.get("database").propertyNames()).containsExactlyInAnyOrder("commitsPerFlush",
				"meanLockWaitMs", "meanCommitWaitMs");
		assertThat(simulated.at("/database/commitsPerFlush").doubleValue()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void simulate_calibratedMemory_movesNoLatency() {
		ObjectNode defaults = withoutMemory(latestInputs());

		ObjectNode plain = SIMULATIONS.simulate(Designs.design(PG_MIXED, defaults, JsonText.read("1500"), SHORT));
		ObjectNode fitted = SIMULATIONS
			.simulate(Designs.design(PG_MIXED, withMemory(defaults), JsonText.read("1500"), SHORT));

		assertThat(LATENCY.stream().map(fitted::get).toList()).isEqualTo(LATENCY.stream().map(plain::get).toList());
	}

	// 40 MiB + 0.03 MiB x the busiest rate (about 1,500 req/s) + 0.01 MiB per request
	// held, against ArchSim's default of 256 MiB at idle
	@Test
	void simulate_calibratedMemory_isTheServicesMemory() {
		ObjectNode defaults = withoutMemory(latestInputs());

		ObjectNode plain = SIMULATIONS.simulate(Designs.design(PG_MIXED, defaults, JsonText.read("1500"), SHORT));
		ObjectNode fitted = SIMULATIONS
			.simulate(Designs.design(PG_MIXED, withMemory(defaults), JsonText.read("1500"), SHORT));

		assertThat(plain.at("/resources/api/memoryMiB").doubleValue()).isGreaterThan(256);
		assertThat(fitted.at("/resources/api/memoryMiB").doubleValue()).isCloseTo(85, within(3.0));
	}

	@Test
	void simulate_theLoadCurve_costsMoreAtLowLoad() {
		ObjectNode inputs = latestInputs();
		ObjectNode flat = inputs.deepCopy();
		flat.remove("serviceTimeByLoad");
		Designs.Window window = new Designs.Window(2, 6);

		double curved = SIMULATIONS.simulate(Designs.design(PG_MIXED, inputs, JsonText.read("200"), window))
			.at("/resources/api/cores")
			.doubleValue();
		double withoutCurve = SIMULATIONS.simulate(Designs.design(PG_MIXED, flat, JsonText.read("200"), window))
			.at("/resources/api/cores")
			.doubleValue();

		assertThat(curved).isGreaterThan(1.5 * withoutCurve);
	}

	@Test
	void simulate_theLoadCurve_costsTheSameAtTheTopRate() {
		JsonNode calibration = calibration();
		ObjectNode inputs = (ObjectNode) calibration.get("inputs");
		ObjectNode flat = inputs.deepCopy();
		flat.remove("serviceTimeByLoad");
		JsonNode top = calibration.get("staircaseRps").get(calibration.get("staircaseRps").size() - 1);
		Designs.Window window = new Designs.Window(2, 6);

		double curved = SIMULATIONS.simulate(Designs.design(PG_MIXED, inputs, top, window))
			.at("/resources/api/cores")
			.doubleValue();
		double withoutCurve = SIMULATIONS.simulate(Designs.design(PG_MIXED, flat, top, window))
			.at("/resources/api/cores")
			.doubleValue();

		assertThat(curved).isCloseTo(withoutCurve, within(0.02 * withoutCurve));
	}

	private static JsonNode calibration() {
		return ResultFiles.read(RepositoryLayout.current().results().resolve("pg-mixed/calibration.json"))
			.orElseThrow();
	}

	private static ObjectNode latestInputs() {
		return ((ObjectNode) calibration().get("inputs")).deepCopy();
	}

	private static ObjectNode withoutMemory(ObjectNode inputs) {
		ObjectNode defaults = inputs.deepCopy();
		List.of("serviceMemoryBaselineMb", "serviceMemoryPerRpsMb", "serviceMemoryPerRequestMb")
			.forEach(defaults::remove);
		return defaults;
	}

	private static ObjectNode withMemory(ObjectNode inputs) {
		ObjectNode fitted = inputs.deepCopy();
		fitted.putObject("serviceMemoryBaselineMb").put("value", 40.0);
		fitted.putObject("serviceMemoryPerRpsMb").put("value", 0.03);
		fitted.putObject("serviceMemoryPerRequestMb").put("value", MemoryFits.MIN_PER_REQUEST_MB);
		return fitted;
	}

}
