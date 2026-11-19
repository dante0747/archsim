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
import static org.assertj.core.api.Assertions.withinPercentage;

/**
 * The instant estimate against a run on a database's commit (ADR-0089): a write waits for
 * the log's flush with its connection held and no core at work, in the run and in the
 * estimate alike. A PostgreSQL of 2 cores takes 50 writes a second, each a query of 4 ms
 * on a core, then a flush of 6 ms: its queries keep 10% of its cores busy, where the
 * flush counted as CPU would make it 25%, and a write holds its connection for the query
 * and the flush. The design is simulated once, for every test.
 */
@TestInstance(Lifecycle.PER_CLASS)
class CommitFlushAgreementTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** The share of its cores the database's queries keep busy. */
	private static final double QUERIES_BUSY = 50 * 4.0 / 1000 / 2;

	/** How far a share measured over the run may be from the estimated one. */
	private static final double UTILIZATION_POINTS = 0.01;

	/**
	 * A commit that arrives during a flush waits for it too, which the estimate leaves
	 * out.
	 */
	private static final double HOLD_TOLERANCE_PERCENT = 15;

	private final JsonNode report = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped())
		.run(JSON.readTree("""
				{"schemaVersion": "1.10", "kind": "archsim.architecture", "metadata": {"name": "commits"},
				 "architecture": {"autoSize": false, "nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"workersPerReplica": 2000}},
				    {"id": "db", "type": "database", "config": {"technology": "postgresql", "cpuCores": 2,
				      "cpuShare": 1.0, "maxConnections": 400, "rowLocks": "off",
				      "writeServiceTimeMs": {"kind": "constant", "value": 4},
				      "commitFlushMs": {"kind": "constant", "value": 6}}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				    {"id": "api-db", "source": "api", "target": "db"}]},
				 "workload": {"rps": 50, "readRatio": 0, "durationS": 60, "warmupS": 5}}"""), RunRequest.of(null));

	@Test
	void run_aDatabaseThatFlushesCommits_estimatesItsQueriesShareOfItsCores() {
		assertThat(estimated().required("cpuUtilization").doubleValue()).isEqualTo(QUERIES_BUSY);
	}

	@Test
	void run_aDatabaseThatFlushesCommits_keepsItsCoresAsBusyAsEstimated() {
		JsonNode simulated = ReferenceDesigns.summaryOf(this.report, "db");

		assertThat(simulated.required("cpuCoreUtilization").doubleValue())
			.isCloseTo(estimated().required("cpuUtilization").doubleValue(), within(UTILIZATION_POINTS));
	}

	@Test
	void run_aDatabaseThatFlushesCommits_holdsAConnectionAboutAsLongAsEstimated() {
		JsonNode simulated = ReferenceDesigns.summaryOf(this.report, "db");

		assertThat(simulated.required("meanHoldMs").doubleValue())
			.isCloseTo(estimated().required("meanHoldMs").doubleValue(), withinPercentage(HOLD_TOLERANCE_PERCENT));
	}

	// the database's part in the estimate the report records
	private JsonNode estimated() {
		return this.report.at("/estimate/parts")
			.valueStream()
			.filter((part) -> "db".equals(part.path("id").stringValue()))
			.findFirst()
			.orElseThrow();
	}

}
