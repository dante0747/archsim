package io.archsim.engine.sizing.audit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.parity.Repository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-sizing against what the simulation then does, on every template
 * ({@link SizingAudit}). Each template's design as drawn is simulated, compressed to
 * {@link #SECONDS}, and checked: CPU at 25-75% of each auto-sized part's vCPU at the p95
 * (or a stated reason: the smallest type, capped by its workers, a bigger type for its
 * memory, its load held back upstream, or the run's bottleneck by its workers); no
 * auto-sized part the bottleneck because of its CPU or memory; memory peak ≤ need ≤
 * instance; a re-run of the input reproduces the types and cost; a higher request rate
 * never gets a cheaper type or one short of memory; per-request cost lines within 10% of
 * a hand calculation; and the monthly cost at most 1.15 times the reference below.
 */
class SizingAuditTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** How long each template's run is compressed to, in seconds. */
	private static final double SECONDS = 15.0;

	/**
	 * Monthly cost ($) of the instance types picked by hand for each template's stated
	 * peak, at the 60% CPU target: the cheapest type that covers each part's CPU and
	 * memory, a t4g type where its CPU at the base load stays under the baseline;
	 * per-request lines at the run's base load (a {@link #SECONDS}-long run). Recorded on
	 * 2026-10-28 (engine 0.13.0, the AWS snapshot of that date), where the hand pick
	 * matched auto-sizing on every template; the bound catches a change that makes
	 * auto-sizing dearer. Black Friday's adds its 24 peak hours a month (ADR-0074),
	 * worked out on 2026-11-15; the SQL Server template's is for its database's 16 vCPU
	 * at schema 1.11, worked out on 2026-11-20.
	 * <p>
	 * What the run measured on 2026-10-28 is lower on six templates (the cheapest types
	 * their simulated p95 CPU allows): auth-token -$72, black-friday -$283 (its checkout
	 * saturates by design, so the database gets less than the peak), iot-cassandra -$336,
	 * kafka -$341, llm-rag -$24, social-feed -$150. That is what right-sizing from the
	 * run is for; sizing for the design's stated peak is what auto-sizing promises.
	 */
	private static final Map<String, Double> REFERENCE = references();

	@ParameterizedTest(name = "{0}", quoteTextArguments = false)
	@MethodSource("templates")
	void audit_aTemplate_holdsUpInTheSimulation(String template) {
		JsonNode design = JSON.readTree(Repository.examples().resolve(template + ".json").toFile());

		List<AuditCheck> checks = SizingAudit.audit(design, SECONDS, Objects.requireNonNull(REFERENCE.get(template)));

		// every template's run and cost; the rest per auto-sized part
		assertThat(checks).extracting(AuditCheck::criterion).contains("rerun", "cost");
		assertThat(checks).filteredOn((check) -> !check.ok()).isEmpty();
	}

	@Test
	void reference_everyTemplate_hasOne() throws IOException {
		assertThat(REFERENCE.keySet()).containsExactlyElementsOf(templates().toList());
	}

	@Test
	void compressed_aTimeline_keepsItsSharesOfTheRun() {
		JsonNode design = JSON.readTree("""
				{"scenarios": [{"id": "x"}], "workload": {"durationS": 120, "warmupS": 10,
				 "pattern": {"kind": "burst", "startS": 48, "durationS": 12},
				 "faults": [{"startS": 60, "durationS": 30}]}}""");

		JsonNode compressed = TemplateRuns.compressed(design, 15.0);

		assertThat(compressed.path("scenarios").isEmpty()).isTrue();
		assertThat(compressed.at("/workload/durationS").doubleValue()).isEqualTo(15.0);
		assertThat(compressed.at("/workload/warmupS").doubleValue()).isEqualTo(1.25);
		assertThat(compressed.at("/workload/pattern/startS").doubleValue()).isEqualTo(6.0);
		assertThat(compressed.at("/workload/faults/0/durationS").doubleValue()).isEqualTo(3.75);
	}

	@Test
	void compressed_aTrace_movesItsPointsInTime() {
		JsonNode design = JSON.readTree("""
				{"workload": {"durationS": 30, "warmupS": 3,
				 "pattern": {"kind": "trace", "points": [[0, 100], [10, 200], [29, 50]]}}}""");

		JsonNode compressed = TemplateRuns.compressed(design, 15.0);

		assertThat(compressed.at("/workload/pattern/points").toString()).isEqualTo("[[0.0,100],[5.0,200],[14.5,50]]");
	}

	@Test
	void compressed_aRunNoLongerThanTheTime_isUnchangedButForItsScenarios() {
		JsonNode design = JSON.readTree("""
				{"scenarios": [{"id": "x"}], "workload": {"durationS": 10, "warmupS": 2}}""");

		JsonNode compressed = TemplateRuns.compressed(design, 15.0);

		assertThat(compressed.path("workload")).isEqualTo(design.path("workload"));
		assertThat(compressed.path("scenarios").isEmpty()).isTrue();
	}

	@Test
	void scaled_aDesign_multipliesItsRates() {
		JsonNode design = JSON.readTree("""
				{"workload": {"rps": 100, "durationS": 60,
				 "pattern": {"kind": "ramp", "startRps": 50, "endRps": 200, "burstRps": 300, "startS": 10}}}""");

		JsonNode scaled = TemplateRuns.scaled(design, 1.5);

		assertThat(
				List.of(scaled.at("/workload/rps").doubleValue(), scaled.at("/workload/pattern/startRps").doubleValue(),
						scaled.at("/workload/pattern/endRps").doubleValue(),
						scaled.at("/workload/pattern/burstRps").doubleValue()))
			.containsExactly(150.0, 75.0, 300.0, 450.0);
		assertThat(scaled.at("/workload/pattern/startS").doubleValue()).isEqualTo(10.0);
	}

	@Test
	void scaled_aTrace_multipliesItsRatesNotItsTimes() {
		JsonNode design = JSON.readTree("""
				{"workload": {"rps": 100, "pattern": {"kind": "trace", "points": [[0, 100], [10, 200]]}}}""");

		JsonNode scaled = TemplateRuns.scaled(design, 2.0);

		assertThat(scaled.at("/workload/pattern/points").toString()).isEqualTo("[[0,200.0],[10,400.0]]");
	}

	@Test
	void p95_twentyValues_isTheNineteenth() {
		List<Double> values = Stream.iterate(20.0, (value) -> value - 1).limit(20).toList();

		assertThat(AuditedRun.p95(values)).isEqualTo(19.0);
	}

	@Test
	void p95_noValues_isZero() {
		assertThat(AuditedRun.p95(List.of())).isZero();
	}

	@ParameterizedTest
	@CsvSource({ "1.0, 1.0", "3.0, 4.0", "4.0, 4.0", "4.5, 8.0", "8.0, 8.0", "1e-9, 1.862645149230957E-9" })
	void powerOfTwoAtLeast_aValue_isTheSmallestPowerOfTwoAtOrAboveIt(double value, double power) {
		assertThat(CpuCheck.powerOfTwoAtLeast(value)).isEqualTo(power);
	}

	// every template in examples/, by name
	static Stream<String> templates() throws IOException {
		try (Stream<Path> files = Files.list(Repository.examples())) {
			return files.map((file) -> file.getFileName().toString())
				.filter((name) -> name.endsWith(".json"))
				.map((name) -> name.substring(0, name.length() - ".json".length()))
				.sorted()
				.toList()
				.stream();
		}
	}

	// each template's reference, a note above it where the figure needs one
	private static Map<String, Double> references() {
		Map<String, Double> references = new TreeMap<>();
		// db: 1.44 cores busy at the peak, 4 vCPU (2 would run 72%)
		references.put("auth-token-introspection", 501.0);
		// orders DB: 2.06 cores at the 5x ramp's peak, 4 vCPU; $1.1299 an hour in all.
		// Each of the 24 peak hours adds 8 checkout pods (11 at the peak) at
		// $0.0756/h and the 3 DB nodes' surplus CPU credits, 3 x (2.064 - 1.6
		// baseline cores) x $0.075/vCPU-h: 730 x 1.1299 + 24 x 0.7092 = $841.8
		references.put("black-friday-kubernetes", 842.0);
		// load balancer t4g.micro $0.0084/h, 2 API servers c6g.xlarge $0.136/h, Redis
		// cache.t4g.micro $0.016/h, PostgreSQL db.m6g.large $0.159/h plus 10 GiB at
		// $0.115/GiB-month: 730 x 0.4554 + 1.15 = $333.6
		references.put("cache-aside-starter", 334.0);
		references.put("cdn-video-streaming", 1821.0);
		references.put("cqrs-kafka-read-model", 3011.0);
		references.put("flash-sale-order-flow", 1391.0);
		// ring: Cassandra on full cores (c6g.medium), not a 1-GiB t4g
		references.put("iot-cassandra-quorum", 768.0);
		// db: 3.13 cores at the peak, 8 vCPU (4 would run 78%): $1,610 of parts; the
		// e-mail API's 55.1 calls/s at the base, 144.92 M a month at Amazon SES's $100 a
		// million calls, $14,492
		references.put("kafka-order-pipeline", 16101.0);
		references.put("kubernetes-pods-pending", 232.0);
		references.put("kubernetes-shared-pool", 845.0);
		// nearly all third-party LLM calls at the base load
		references.put("llm-rag-assistant", 189276.0);
		references.put("microservices-call-chain", 1089.0);
		references.put("mongodb-hot-shard", 1254.0);
		references.put("mysql-replica-lag", 1289.0);
		references.put("nats-live-telemetry", 2242.0);
		// nearly all SMS and email calls at the base load
		references.put("notification-fanout-quotas", 283532.0);
		references.put("public-api-slow-dependency", 1246.0);
		references.put("queueing-lab", 73.0);
		// db: 10 connections x 50% CPU, 8 vCPU at most (was 32)
		references.put("retry-storm-ttl-cache", 993.0);
		references.put("right-size-catalog-api", 672.0);
		references.put("search-opensearch-catalog", 2538.0);
		// worked out by hand for the frozen Python engine's managed-services cost test
		// (test_cost_managed.py), on the same snapshot
		references.put("serverless-burst", 2007.0);
		references.put("serverless-image-pipeline", 19365.0);
		// cache: its keys +25% need a cache.r6g.xlarge
		references.put("social-feed-hot-key", 1817.0);
		// parts as set: 4 API servers c6i.xlarge $0.17/h; the db's 16 vCPU (8 cores
		// of two threads), a c6i.4xlarge with its storage, $0.6811/h; the load
		// balancer $0.0225/h and 39,420 LCU-hours at $0.008: 730 x 1.3836 + 315.36
		// = $1,325.4
		references.put("sqlserver-saas-blocking", 1325.0);
		return references;
	}

}
