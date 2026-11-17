package io.archsim.engine.simulator;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.assertj.core.api.Assertions.withinPercentage;

/**
 * A database of several instances reports its busiest instance's CPU beside the mean over
 * them all, and its CPU ceiling is where that instance's cores fill, from the work it is
 * sent (ADR-0083). The replicated database takes 50 writes/s of 10 ms on its primary and
 * 50 reads/s of 2 ms over its three instances, half of each on 2 cores: its primary works
 * 266.7 ms a second, 13.3% of its cores, and fills them at 750 req/s.
 */
@TestInstance(Lifecycle.PER_CLASS)
class BusiestInstanceTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** The database's cores, each instance's. */
	private static final double CORES = 2;

	/** The share of an operation's time spent on its CPU. */
	private static final double CPU_SHARE = 0.5;

	/** A write's time, in milliseconds. */
	private static final double WRITE_MS = 10;

	/** A read's time, in milliseconds. */
	private static final double READ_MS = 2;

	/** The primary's share of its cores busy: its writes, and a third of the reads. */
	private static final double PRIMARY_BUSY = (50 * WRITE_MS + 50 / 3.0 * READ_MS) * CPU_SHARE / 1000 / CORES;

	/** The rate at which the primary's cores fill. */
	private static final double PRIMARY_CEILING = 100 / PRIMARY_BUSY;

	/**
	 * The ceiling of a primary that takes only the writes, half the requests, all of a
	 * write's time on its CPU: 2 cores fill at 2,000 ms ÷ 5 ms a request.
	 */
	private static final double WRITES_ONLY_CEILING = 400;

	/** The CPU ceiling's formula, worked out: 2 cores over the work per request. */
	private static final Pattern WORK_PER_REQUEST = Pattern.compile("2 × 1000 ÷ ([0-9.]+) ms");

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	private final JsonNode replicated = run(100, CORES, CPU_SHARE, "\"readReplicas\": 2");

	private final JsonNode single = run(100, CORES, CPU_SHARE, "\"readReplicas\": 0");

	@Test
	void run_aReplicatedDatabaseTakingWrites_reportsItsPrimaryAsItsBusiestInstance() {
		JsonNode summary = summary(this.replicated);

		assertThat(summary.path("busiestInstanceCpuUtilization").doubleValue()).isCloseTo(PRIMARY_BUSY, offset(0.005));
		assertThat(summary.path("busiestInstanceCpuUtilization").doubleValue())
			.isGreaterThan(summary.path("cpuCoreUtilization").doubleValue());
	}

	@Test
	void run_aReplicatedDatabase_setsItsCpuCeilingWhereItsBusiestInstanceFills() {
		JsonNode summary = summary(this.replicated);

		assertThat(summary.path("cpuCapacityRps").doubleValue()).isCloseTo(PRIMARY_CEILING, withinPercentage(3));
		assertThat(summary.path("capacityLimit").stringValue()).isEqualTo("cpu");
	}

	@Test
	void run_aReplicatedDatabase_explainsItsCeilingByItsBusiestInstance() {
		JsonNode formula = formula(this.replicated, "cpu_capacity");

		assertThat(formula.path("formula").stringValue())
			.isEqualTo("CPU ceiling = cores × 1000 ÷ the busiest instance's CPU work per request");
		Matcher substituted = WORK_PER_REQUEST.matcher(formula.path("substituted").stringValue());
		assertThat(substituted.matches()).isTrue();
		assertThat(Double.parseDouble(substituted.group(1))).isCloseTo(PRIMARY_BUSY * CORES * 1000 / 100,
				withinPercentage(2));
	}

	@Test
	void run_aDatabaseOfOneInstance_reportsNoBusiestInstanceAndKeepsItsCeiling() {
		JsonNode summary = summary(this.single);

		assertThat(summary.has("busiestInstanceCpuUtilization")).isFalse();
		assertThat(summary.path("cpuCapacityRps").doubleValue()).isCloseTo(
				CORES * 1000 / (summary.path("meanServiceMs").doubleValue() * CPU_SHARE), withinPercentage(0.01));
	}

	@Test
	void run_aPrimaryPastItsCeiling_keepsTheCeilingWhereItsCoresFill() {
		String writesOnPrimary = "\"readReplicas\": 2, \"readFrom\": \"replicas\"";

		double below = summary(run(100, CORES, 1.0, writesOnPrimary)).path("cpuCapacityRps").doubleValue();
		double past = summary(run(1000, CORES, 1.0, writesOnPrimary)).path("cpuCapacityRps").doubleValue();

		assertThat(below).isCloseTo(WRITES_ONLY_CEILING, withinPercentage(5));
		assertThat(past).isCloseTo(WRITES_ONLY_CEILING, withinPercentage(5));
	}

	@Test
	void run_aPrimaryPastItsCeiling_namesItsBusiestInstanceInTheBottleneckFinding() {
		JsonNode report = run(1000, CORES, 1.0, "\"readReplicas\": 2, \"readFrom\": \"replicas\"");

		assertThat(report.required("findings").valueStream().map((finding) -> finding.path("detail").stringValue()))
			.anySatisfy((detail) -> assertThat(detail).containsPattern(
					"Its busiest instance's CPU cores, 100% busy, can do about [0-9]{3} req/s; it received 1,0[0-9]{2} "
							+ "req/s\\."));
	}

	@Test
	void run_anIdleReplicaOnHyperthreads_leavesThePrimarysCeilingAsItWas() {
		String hyperthreads = "\"siblingSpeed\": 0.6, \"readFrom\": \"primary\", ";

		double alone = summary(run(300, 4, CPU_SHARE, hyperthreads + "\"readReplicas\": 0")).path("cpuCapacityRps")
			.doubleValue();
		double withAReplica = summary(run(300, 4, CPU_SHARE, hyperthreads + "\"readReplicas\": 1"))
			.path("cpuCapacityRps")
			.doubleValue();

		assertThat(withAReplica).isCloseTo(alone, withinPercentage(3));
	}

	@Test
	void run_aShardedDatabaseWithAHotShard_setsItsCeilingBelowAnEvenSpread() {
		JsonNode summary = summary(run(100, CORES, CPU_SHARE, "\"shards\": 4"));
		double evenSpread = CORES * 4 * 1000 / (summary.path("meanServiceMs").doubleValue() * CPU_SHARE);

		assertThat(summary.path("cpuCapacityRps").doubleValue()).isLessThan(evenSpread * 0.9);
	}

	/**
	 * A service reading and writing a database half and half, its keys Zipf-skewed.
	 * @param rps the requests a second
	 * @param cores each instance's cores
	 * @param cpuShare the share of an operation's time on its CPU
	 * @param settings the database's other settings
	 * @return the report
	 */
	private JsonNode run(double rps, double cores, double cpuShare, String settings) {
		return this.simulator.run(JSON.readTree("""
				{"schemaVersion": "1.10", "kind": "archsim.architecture", "metadata": {"name": "instances"},
				 "architecture": {"autoSize": false, "nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"workersPerReplica": 2000}},
				    {"id": "db", "type": "database", "config": {"cpuCores": %s, "cpuShare": %s,
				      "maxConnections": 400, "queueCapacity": 2000,
				      "readServiceTimeMs": {"kind": "constant", "value": 2},
				      "writeServiceTimeMs": {"kind": "constant", "value": 10}, %s}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				    {"id": "api-db", "source": "api", "target": "db"}]},
				 "workload": {"rps": %s, "readRatio": 0.5, "durationS": 40, "warmupS": 5, "keySkew": 1.2}}"""
			.formatted(cores, cpuShare, settings, rps)), RunRequest.of(null));
	}

	private static JsonNode summary(JsonNode report) {
		return ReferenceDesigns.summaryOf(report, "db");
	}

	private static JsonNode formula(JsonNode report, String id) {
		return report.required("nodes")
			.valueStream()
			.filter((node) -> node.required("id").stringValue().equals("db"))
			.flatMap((node) -> node.required("explain").valueStream())
			.filter((explained) -> explained.required("id").stringValue().equals(id))
			.findFirst()
			.orElseThrow();
	}

}
