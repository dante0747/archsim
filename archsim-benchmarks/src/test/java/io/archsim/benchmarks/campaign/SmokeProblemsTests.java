package io.archsim.benchmarks.campaign;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a smoke test finds wrong: requests k6 did not send or that failed, and requests
 * the service, PostgreSQL, the cache, Redis or the producer did not count.
 */
class SmokeProblemsTests {

	private static final Experiment JAVA = Experiments.standard().get("java-mixed");

	private static final Experiment PYTHON = Experiments.standard().get("python-mixed");

	private static final Experiment CHAIN = Experiments.standard().get("chain");

	private static final Experiment CACHE_TTL = Experiments.standard().get("cache-ttl");

	private static final Experiment KAFKA = Experiments.standard().get("kafka");

	// Node.js's collections, which server.js reports since W21
	private static final String NODE_GC = "\"gc\": {\"collections\": 3, \"pauseMs\": 2.0, \"byKind\": {}}";

	@Test
	void of_aServiceThatCountedEveryRequest_findsNothing() {
		assertThat(problems(JAVA, sent(200, 200, 0), good())).isEmpty();
	}

	@Test
	void of_noGarbageCollectionCounts_saysSo() {
		ObjectNode stats = good();
		stats.remove("gc");

		assertThat(problems(JAVA, sent(200, 200, 0), stats))
			.containsExactly("the service reported no garbage collection counts");
	}

	@Test
	void of_gunicornsTenWorkers_findsNothing() {
		assertThat(problems(PYTHON, sent(200, 200, 0), good().put("workers", 10))).isEmpty();
	}

	@Test
	void of_aGunicornWorkerWithoutAStatisticsSlot_saysSo() {
		assertThat(problems(PYTHON, sent(200, 200, 0), good().put("workers", 9)))
			.containsExactly("9 gunicorn workers hold a statistics slot, not 10");
	}

	// python-wide has four CPUs and still gunicorn's ten workers
	@Test
	void of_pythonWideWithAWorkerMissing_saysSo() {
		assertThat(problems(experiment("python-wide"), sent(200, 200, 0), good().put("workers", 4)))
			.containsExactly("4 gunicorn workers hold a statistics slot, not 10");
	}

	@ParameterizedTest
	@ValueSource(strings = { "go-mixed", "go-pool" })
	void of_aGoServiceWithAThreadForEachOfItsCpus_findsNothing(String experiment) {
		assertThat(problems(experiment(experiment), sent(200, 200, 0), good().put("gomaxprocs", 2))).isEmpty();
	}

	// GOMAXPROCS unset: Go counts the machine's CPUs, not its container's two
	@ParameterizedTest
	@ValueSource(strings = { "go-mixed", "go-pool" })
	void of_aGoServiceWithOtherThreadsThanItsCpus_saysSo(String experiment) {
		assertThat(problems(experiment(experiment), sent(200, 200, 0), good().put("gomaxprocs", 8)))
			.containsExactly("the Go service reported GOMAXPROCS 8, not its 2 CPUs");
	}

	@Test
	void of_aGoServiceThatReportedNoThreads_saysSo() {
		assertThat(problems(experiment("go-pool"), sent(200, 200, 0), good()))
			.containsExactly("the Go service reported GOMAXPROCS None, not its 2 CPUs");
	}

	// java-hot-row on the Java stack: every request a write PostgreSQL timed
	@Test
	void of_aHotRowWhoseEveryWriteWasTimed_findsNothing() {
		ObjectNode stats = counted(200, 0, 200).setAll(JsonText.object("{\"gc\": {\"collections\": 0}}"));

		assertThat(problems(experiment("java-hot-row"), sent(200, 200, 0), stats)).isEmpty();
	}

	@Test
	void of_aHotRowWithWritesNotTimed_findsThemMissingFromPostgres() {
		ObjectNode stats = counted(200, 0, 150).setAll(JsonText.object("{\"gc\": {\"collections\": 0}}"));

		assertThat(problems(experiment("java-hot-row"), sent(200, 200, 0), stats))
			.containsExactly("PostgreSQL calls timed: 150, k6 sent 200");
	}

	// cache-miss on the cache-ttl stack, whose service reports Redis's commands
	@Test
	void of_cacheMissCountingEveryLookup_findsNothing() {
		assertThat(problems(experiment("cache-miss"), sent(200, 200, 0), cacheTtl())).isEmpty();
	}

	@Test
	void of_cacheMissWithRedisReportingNoGets_saysSo() {
		ObjectNode stats = cacheTtl();
		stats.putObject("redis");

		assertThat(problems(experiment("cache-miss"), sent(200, 200, 0), stats))
			.containsExactly("Redis counted None GET commands, k6 sent 200");
	}

	@Test
	void of_failedRequests_countsThem() {
		assertThat(problems(JAVA, sent(200, 197, 0), good())).containsExactly("3 of 200 requests failed");
	}

	@Test
	void of_droppedRequests_countsThem() {
		assertThat(problems(JAVA, sent(200, 200, 4), good()))
			.containsExactly("k6 dropped 4 requests (no VU free to send them)");
	}

	@Test
	void of_tooFewRequestsSent_saysHowFew() {
		assertThat(problems(JAVA, sent(120, 120, 0), good())).first()
			.isEqualTo("k6 sent 120 requests of the 200 it should have");
	}

	@Test
	void of_aRequestTheServiceDidNotCount_findsItMissingFromTheServiceAndPostgres() {
		assertThat(problems(JAVA, sent(200, 200, 0), counted(199, 180, 19)))
			.contains("the service counted 199 requests, k6 sent 200", "PostgreSQL calls timed: 199, k6 sent 200");
	}

	@Test
	void of_noCpuTime_saysSo() {
		assertThat(problems(JAVA, sent(200, 200, 0), good().put("cpuMs", 0))).first()
			.isEqualTo("the service reported no CPU time");
	}

	@Test
	void of_aChainThatCountedEveryRequest_findsNothing() {
		assertThat(problems(CHAIN, sent(200, 200, 0), gateway(counted(200, 180, 20)))).isEmpty();
	}

	@Test
	void of_aChainsApiThatLostRequests_findsThemMissingBehindTheGateway() {
		assertThat(problems(CHAIN, sent(200, 200, 0), gateway(counted(150, 140, 10)))).contains(
				"the API behind the gateway counted 150 requests, k6 sent 200",
				"PostgreSQL calls timed: 150, k6 sent 200");
	}

	@Test
	void of_aChainGatewayWithoutGarbageCollectionCounts_saysSoAlone() {
		ObjectNode gateway = gateway(counted(200, 180, 20));
		gateway.remove("gc");

		assertThat(problems(CHAIN, sent(200, 200, 0), gateway))
			.containsExactly("the service reported no garbage collection counts");
	}

	@Test
	void of_aKafkaProducerThatCountedEveryMessage_findsNothing() {
		assertThat(problems(KAFKA, sent(200, 200, 0), producer("{\"produced:measure\": 200}", NODE_GC))).isEmpty();
	}

	// run.py checked the garbage collection before the producer's messages
	@Test
	void of_aKafkaProducerWithoutCounts_saysSoAfterTheGarbageCollection() {
		assertThat(problems(KAFKA, sent(200, 200, 0), producer("{}", "\"other\": 0"))).containsExactly(
				"the service reported no garbage collection counts", "the producer counted 0 messages, k6 sent 200");
	}

	// a counter reported as None is not one reported as 0
	@Test
	void of_aKafkaProducerCountingNone_printsNone() {
		assertThat(problems(KAFKA, sent(200, 200, 0), producer("{\"produced:measure\": null}", NODE_GC)))
			.containsExactly("the producer counted None messages, k6 sent 200");
	}

	@Test
	void of_aCacheThatCountedEveryLookup_findsNothing() {
		assertThat(problems(CACHE_TTL, sent(200, 200, 0), cacheTtl())).isEmpty();
	}

	@Test
	void of_aCacheThatCountedNoLookup_saysSo() {
		ObjectNode stats = cacheTtl();
		stats.putObject("counters");

		assertThat(problems(CACHE_TTL, sent(200, 200, 0), stats))
			.containsExactly("the cache counted 0 hits and misses, k6 sent 200");
	}

	// INFO commandstats not parsed: no GET counted
	@Test
	void of_redisReportingNoGets_saysSo() {
		ObjectNode stats = cacheTtl();
		stats.putObject("redis");

		assertThat(problems(CACHE_TTL, sent(200, 200, 0), stats))
			.containsExactly("Redis counted None GET commands, k6 sent 200");
	}

	private static Experiment experiment(String id) {
		return Experiments.standard().get(id);
	}

	private static List<String> problems(Experiment experiment, JsonNode sent, JsonNode stats) {
		return SmokeProblems.of(experiment, sent, stats, Smoke.RPS * Smoke.SECONDS);
	}

	// k6's measured window
	private static JsonNode sent(int sent, int succeeded, int dropped) {
		return JsonText.read("{\"sent\": %d, \"succeeded\": %d, \"failed\": %d, \"dropped\": %d}".formatted(sent,
				succeeded, sent - succeeded, dropped));
	}

	// a service's /stats: its requests and handlers, and its calls to PostgreSQL
	private static ObjectNode counted(int requests, int reads, int writes) {
		return JsonText.object("""
				{"requests": %d, "cpuMs": 12.5, "counters": {},
				 "timings": {"handler": {"count": %d}, "db_read": {"count": %d}, "db_write": {"count": %d}}}"""
			.formatted(requests, requests, reads, writes));
	}

	private static ObjectNode good() {
		return counted(200, 181, 19).setAll(JsonText.object("{\"gc\": {\"collections\": 0, \"pauseMs\": 0.0}}"));
	}

	// chain's gateway, which calls the API and reports its /stats as upstream
	private static ObjectNode gateway(ObjectNode api) {
		ObjectNode gateway = JsonText.object("""
				{"requests": 200, "cpuMs": 12.5, "counters": {},
				 "timings": {"handler": {"count": 200}, "upstream": {"count": 200}}, %s}""".formatted(NODE_GC));
		gateway.set("upstream", api);
		return gateway;
	}

	// Kafka's producer's /stats: its counters and its garbage collection, or something
	// else
	private static ObjectNode producer(String counters, String gc) {
		return JsonText.object("""
				{"requests": 200, "cpuMs": 12.5, "timings": {"handler": {"count": 200}}, "counters": %s, %s}"""
			.formatted(counters, gc));
	}

	private static ObjectNode cacheTtl() {
		return JsonText.object("""
				{"requests": 200, "cpuMs": 12.5, "timings": {"handler": {"count": 200}},
				 "counters": {"cache_hit:measure": 120, "cache_miss:measure": 80},
				 "redis": {"get": {"calls": 200, "usec": 1200}, "set": {"calls": 80, "usec": 500}}, %s}"""
			.formatted(NODE_GC));
	}

}
