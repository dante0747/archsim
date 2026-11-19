package io.archsim.benchmarks.experiments;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.tuple;

class ExperimentsTests {

	private static final Experiments EXPERIMENTS = Experiments.standard();

	private static final HeldOut FIRST_SET = new HeldOut("2026-10-30", "ce2d109d534b9a9cd18a4a42690b71dcf86342f4");

	/** The second set's registration (ADR-0090). */
	private static final HeldOut SECOND_SET = new HeldOut("2026-11-19", "61ceaa32f27e4bfe601edab6922f3c342c3b9580");

	@Test
	void standard_theExperiments_areTheTunedOnFourThenTheTwoHeldOutSets() {
		List<String> ids = EXPERIMENTS.all().stream().map(Experiment::id).toList();

		assertThat(ids).containsExactly("pg-mixed", "pg-hot-row", "cache-aside", "kafka", "java-mixed", "go-mixed",
				"python-mixed", "chain", "cache-ttl", "python-cores", "python-wide", "java-hot-row", "go-pool",
				"cache-miss");
	}

	@Test
	void standard_theHeldOutExperiments_wereRegisteredInTwoSets() {
		List<HeldOut> registrations = EXPERIMENTS.all()
			.stream()
			.filter(Experiment::isHeldOut)
			.map(Experiment::heldOut)
			.distinct()
			.toList();

		assertThat(registrations).containsExactly(FIRST_SET, SECOND_SET);
	}

	@Test
	void standard_theFirstHeldOutSet_runsOnFourRuntimesEachOnTheStackNamedAfterIt() {
		List<Experiment> first = registeredAs(FIRST_SET);

		assertThat(first).extracting(Experiment::runtime).containsExactly("java", "go", "python", "nodejs", "nodejs");
		assertThat(first).allSatisfy((experiment) -> assertThat(experiment.stack()).isEqualTo(experiment.id()));
	}

	// three on stacks of their own, two more on the Java and the cache-ttl stacks
	@Test
	void standard_theSecondHeldOutSet_runsOnThreeNewStacksAndTwoOfTheFirstSets() {
		List<Experiment> second = registeredAs(SECOND_SET);

		assertThat(second).extracting(Experiment::id, Experiment::stack, Experiment::runtime)
			.containsExactly(tuple("python-cores", "python-cores", "python"),
					tuple("python-wide", "python-wide", "python"), tuple("java-hot-row", "java-mixed", "java"),
					tuple("go-pool", "go-pool", "go"), tuple("cache-miss", "cache-ttl", "nodejs"));
	}

	// pg-mixed's workload on three more runtimes, chain the same through a gateway, and
	// Flask again on other CPUs
	@ParameterizedTest
	@ValueSource(strings = { "java-mixed", "go-mixed", "python-mixed", "chain", "python-cores", "python-wide" })
	void get_aHeldOutRunOfPgMixedsWorkload_hasItsTrafficAndCalibration(String id) {
		Experiment experiment = EXPERIMENTS.get(id);
		Experiment pgMixed = EXPERIMENTS.get("pg-mixed");

		assertThat(experiment)
			.extracting(Experiment::mix, Experiment::readRatio, Experiment::keys, Experiment::skew,
					Experiment::calibration)
			.containsExactly(pgMixed.mix(), pgMixed.readRatio(), pgMixed.keys(), pgMixed.skew(), pgMixed.calibration());
	}

	@Test
	void get_cacheTtl_isItsOwnStackWithAFiveSecondTtl() {
		Experiment cacheTtl = EXPERIMENTS.get("cache-ttl");

		assertThat(cacheTtl)
			.extracting(Experiment::stack, Experiment::mix, Experiment::ttlS, Experiment::keys, Experiment::skew)
			.containsExactly("cache-ttl", "cached:1", 5, 1_000_000, 1.1);
	}

	@Test
	void get_javaHotRow_hasPgHotRowsTrafficAndCalibration() {
		Experiment javaHotRow = EXPERIMENTS.get("java-hot-row");
		Experiment pgHotRow = EXPERIMENTS.get("pg-hot-row");

		assertThat(javaHotRow)
			.extracting(Experiment::mix, Experiment::readRatio, Experiment::keys, Experiment::skew,
					Experiment::calibration)
			.containsExactly("hot:1", 0.0, 1, 0.0, pgHotRow.calibration());
	}

	@Test
	void get_goPool_isHalfReadsAndHalfWritesOverPgMixedsRows() {
		Experiment goPool = EXPERIMENTS.get("go-pool");

		assertThat(goPool)
			.extracting(Experiment::mix, Experiment::readRatio, Experiment::keys, Experiment::skew,
					Experiment::calibration)
			.containsExactly("read:0.5,write:0.5", 0.5, 100_000, 0.0, List.of("read:0.5,write:0.5", "write_async:1"));
	}

	// cache-ttl's stack and keys, drawn uniformly: at 2,500 req/s about 1% of them hit
	@Test
	void get_cacheMiss_drawsCacheTtlsMillionKeysUniformly() {
		Experiment cacheMiss = EXPERIMENTS.get("cache-miss");

		assertThat(cacheMiss)
			.extracting(Experiment::mix, Experiment::readRatio, Experiment::ttlS, Experiment::keys, Experiment::skew,
					Experiment::calibration)
			.containsExactly("cached:1", 1.0, 5, 1_000_000, 0.0, List.of("cached:1"));
	}

	// what the comparison and the page call each: its service, its CPUs or pool, and its
	// traffic. python-cores' says how Docker's VM numbers CPUs 0 and 2, not which cores
	// of the machine they are: Windows schedules the VM's CPUs itself
	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"python-cores | Flask on gunicorn (10 sync workers) on two CPUs the VM numbers as separate cores + "
					+ "PostgreSQL: 90% reads, 10% writes over 100,000 rows",
			"python-wide | Flask on gunicorn (10 sync workers) on four CPUs + PostgreSQL: 90% reads, 10% writes "
					+ "over 100,000 rows",
			"java-hot-row | Spring Boot (Tomcat, HikariCP) + PostgreSQL: every request updates the same row",
			"go-pool | Go (net/http, pgx) + PostgreSQL through a pool of 2 connections: 50% reads, 50% writes over "
					+ "100,000 rows",
			"cache-miss | Cache-aside: Node.js, Redis (5 s TTL) and PostgreSQL; uniform over 1,000,000 keys, so "
					+ "nearly every lookup misses" })
	void get_anExperimentOfTheSecondSet_hasItsTitle(String id, String title) {
		assertThat(EXPERIMENTS.get(id).title()).isEqualTo(title);
	}

	@Test
	void stacks_theExperiments_runInACampaignsOrderOnElevenStacks() {
		Map<String, List<String>> stacks = EXPERIMENTS.stacks();

		assertThat(stacks.keySet()).containsExactly("postgres", "cache", "kafka", "java-mixed", "go-mixed",
				"python-mixed", "chain", "cache-ttl", "python-cores", "python-wide", "go-pool");
		assertThat(stacks).containsEntry("postgres", List.of("pg-mixed", "pg-hot-row"))
			.containsEntry("java-mixed", List.of("java-mixed", "java-hot-row"))
			.containsEntry("cache-ttl", List.of("cache-ttl", "cache-miss"));
	}

	@Test
	void stacks_everyExperiment_runsOnItsStackAndNoOther() {
		assertThat(EXPERIMENTS.all()).allSatisfy((experiment) -> assertThat(EXPERIMENTS.stacks()
			.entrySet()
			.stream()
			.filter((stack) -> stack.getValue().contains(experiment.id()))
			.map(Map.Entry::getKey)).containsExactly(experiment.stack()));
	}

	@Test
	void hasCache_theExperiments_areTheThreeOnARedisStack() {
		List<String> cached = EXPERIMENTS.all().stream().filter(Experiment::hasCache).map(Experiment::id).toList();

		assertThat(cached).containsExactly("cache-aside", "cache-ttl", "cache-miss");
	}

	@Test
	void runsOnNode_theExperiments_areTheSevenWithANodeService() {
		List<String> node = EXPERIMENTS.all().stream().filter(Experiment::runsOnNode).map(Experiment::id).toList();

		assertThat(node).containsExactly("pg-mixed", "pg-hot-row", "cache-aside", "kafka", "chain", "cache-ttl",
				"cache-miss");
	}

	// every request a write to one key: the row's lock is a capacity of its own
	@Test
	void updatesOneRow_theExperiments_areTheTwoHotRows() {
		List<String> hot = EXPERIMENTS.all().stream().filter(Experiment::updatesOneRow).map(Experiment::id).toList();

		assertThat(hot).containsExactly("pg-hot-row", "java-hot-row");
	}

	@Test
	void connections_goPool_areItsPoolOfTwo() {
		assertThat(EXPERIMENTS.get("go-pool").connections()).isEqualTo(2);
	}

	@Test
	void connections_anExperimentThatNamesNoPool_areTen() {
		List<Experiment> others = EXPERIMENTS.all()
			.stream()
			.filter((experiment) -> !experiment.id().equals("go-pool"))
			.toList();

		assertThat(others).extracting(Experiment::connections).containsOnly(10);
	}

	@Test
	void runtime_pythonMixed_isGunicornsTenSyncWorkers() {
		ServiceRuntime runtime = EXPERIMENTS.runtime(EXPERIMENTS.get("python-mixed"));

		assertThat(runtime).extracting(ServiceRuntime::technology, ServiceRuntime::cpuCores, ServiceRuntime::workers)
			.containsExactly("python", 2, 10);
	}

	// the same service and workers as python-mixed, on four CPUs
	@Test
	void runtime_pythonWide_isGunicornsTenWorkersOnFourCpus() {
		ServiceRuntime runtime = EXPERIMENTS.runtime(EXPERIMENTS.get("python-wide"));

		assertThat(runtime).isEqualTo(EXPERIMENTS.runtime("python").onCpus(4));
		assertThat(runtime).extracting(ServiceRuntime::technology, ServiceRuntime::cpuCores, ServiceRuntime::workers)
			.containsExactly("python", 4, 10);
	}

	@Test
	void runtime_anExperimentThatNamesNoCpus_isItsRuntimeAsItIs() {
		List<Experiment> others = EXPERIMENTS.all()
			.stream()
			.filter((experiment) -> !experiment.id().equals("python-wide"))
			.toList();

		assertThat(others).allSatisfy((experiment) -> assertThat(EXPERIMENTS.runtime(experiment))
			.isEqualTo(EXPERIMENTS.runtime(experiment.runtime())));
	}

	@Test
	void read_anExperimentWithAnUnknownRuntime_fails() {
		String catalog = """
				{"runtimes": {}, "stacks": {}, "experiments": [{"id": "x", "stack": "s", "title": "t", "mix": "m",
				  "readRatio": 0.5, "keys": 1, "skew": 0.0, "calibration": [], "runtime": "cobol", "ttlS": 30}]}""";

		assertThatIllegalStateException().isThrownBy(() -> Experiments.read(text(catalog)))
			.withMessage("x's runtime cobol is not one of []");
	}

	@Test
	void read_aStackNamingNoExperiment_fails() {
		String catalog = """
				{"runtimes": {}, "experiments": [], "stacks": {"postgres": ["pg-mixed"]}}""";

		assertThatIllegalStateException().isThrownBy(() -> Experiments.read(text(catalog)))
			.withMessage("stack postgres runs pg-mixed, which is no experiment");
	}

	private static List<Experiment> registeredAs(HeldOut registration) {
		return EXPERIMENTS.all().stream().filter((experiment) -> registration.equals(experiment.heldOut())).toList();
	}

	private static InputStream text(String json) {
		return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
	}

}
