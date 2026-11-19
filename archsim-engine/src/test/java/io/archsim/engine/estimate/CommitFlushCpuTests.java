package io.archsim.engine.estimate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.estimate.RecordedEstimates.RecordedEstimate;
import io.archsim.engine.estimate.RecordedEstimates.Source;
import io.archsim.engine.load.CacheMisses;
import io.archsim.engine.load.LoadEstimate;
import io.archsim.engine.load.LoadEstimator;
import io.archsim.engine.load.NodeLoad;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * How far the Java's estimate of a database's CPU is from the frozen engine's, for every
 * database of every design recorded with the frozen engine's estimate of it
 * ({@link RecordedEstimates}). Python counted the log flush a write waits for as work on
 * the database's cores (ADR-0089), so its share of the cores busy over the Java's is the
 * database's queries and flushes over its queries, at any speed of its threads, and
 * nothing else moves it. {@code CommitFlushDepartures} reads the Java's lower share as
 * Python's; this holds the share to the amount.
 */
class CommitFlushCpuTests {

	private static final String CPU = "cpuUtilization";

	private static final double MILLIS_PER_SECOND = 1000.0;

	/** How far a share rounded to four decimals may be from the unrounded one. */
	private static final double ROUNDING = 5e-5;

	/** Floating-point noise. */
	private static final double NOISE = 1e-9;

	private final InstantEstimator estimator = new InstantEstimator(StandardComponents.registry());

	private final LoadEstimator loads = new LoadEstimator(StandardComponents.registry());

	@TestFactory
	Stream<DynamicTest> estimate_eachRecordedDatabase_hasPythonsCpuOverItsOwnAsItsFlushesAndQueriesOverItsQueries()
			throws IOException {
		return RecordedEstimates.all()
			.stream()
			.flatMap((design) -> databases(design).stream())
			.map((database) -> DynamicTest.dynamicTest(database.name(),
					() -> assertThat(database.pythonCpu() * database.queriesCpu()).isCloseTo(
							database.javaCpu() * (database.queriesCpu() + database.flushCpu()),
							within(database.rounding()))));
	}

	@Test
	void estimate_everyKindOfRecording_hasDatabasesToCheck() throws IOException {
		List<Source> checked = RecordedEstimates.all()
			.stream()
			.filter((design) -> !databases(design).isEmpty())
			.map(RecordedEstimate::source)
			.distinct()
			.toList();

		assertThat(checked).containsExactlyInAnyOrder(Source.values());
	}

	// the databases of a design whose CPU the frozen engine estimated
	private List<Database> databases(RecordedEstimate recorded) {
		ArchitectureDocument design = recorded.design();
		Workload workload = design.workload();
		double peak = workload.peakRate();
		Map<String, Double> misses = this.loads.ttlMissShares(design.architecture(), workload, peak);
		LoadEstimate load = this.loads.estimate(design.architecture(), workload, peak, new CacheMisses(misses, null));
		EstimateGraph graph = EstimateGraph.of(design.architecture(), StandardComponents.registry());
		JsonNode java = this.estimator.estimate(design).toJson().path("parts");
		JsonNode python = recorded.python().path("parts");
		List<Database> databases = new ArrayList<>();
		for (int index = 0; index < python.size(); index++) {
			JsonNode part = python.get(index);
			String id = part.path("id").asString();
			if (part.path(CPU).isNumber() && graph.config(id).orElse(null) instanceof DatabaseConfig config) {
				NodeLoad carried = load.node(id).orElse(NodeLoad.NONE);
				databases.add(new Database(recorded.source() + " " + recorded.name() + " " + id,
						part.path(CPU).doubleValue(), java.get(index).path(CPU).doubleValue(),
						queriesMs(config, carried) * coresPerMs(config),
						flushesMs(config, carried) * coresPerMs(config)));
			}
		}
		return databases;
	}

	// the milliseconds of queries a second its primary takes: its share of the reads,
	// and every write
	private static double queriesMs(DatabaseConfig config, NodeLoad load) {
		return (load.read() / (1 + config.readReplicas())) * config.readServiceTimeMs().expected()
				+ load.write() * config.writeServiceTimeMs().expected();
	}

	// the milliseconds of log flushes a second its writes wait for
	private static double flushesMs(DatabaseConfig config, NodeLoad load) {
		return config.flushesCommits() ? load.write() * config.commitFlushMs().expected() : 0.0;
	}

	// the share of its primary's cores a millisecond a second keeps busy, were all of
	// it work as a query's CPU share is
	private static double coresPerMs(DatabaseConfig config) {
		return config.cpuShare() / MILLIS_PER_SECOND / Math.max(1, config.shards())
				/ Objects.requireNonNull(config.cpuCores());
	}

	/**
	 * A recorded database's CPU in both estimates, and what its load keeps busy at full
	 * speed.
	 *
	 * @param name the recording and the database
	 * @param pythonCpu the share of its cores busy the frozen engine estimates
	 * @param javaCpu the share the Java estimates
	 * @param queriesCpu the share its queries' CPU work keeps busy
	 * @param flushCpu the share its writes' log flushes would keep busy, were they work
	 */
	private record Database(String name, double pythonCpu, double javaCpu, double queriesCpu, double flushCpu) {

		/**
		 * How far the two sides of the rule may be apart: each share is rounded to four
		 * decimals.
		 * @return the tolerance
		 */
		double rounding() {
			return ROUNDING * (2 * this.queriesCpu + this.flushCpu) + NOISE;
		}

	}

}
