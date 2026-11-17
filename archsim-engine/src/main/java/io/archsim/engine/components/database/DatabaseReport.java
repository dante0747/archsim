package io.archsim.engine.components.database;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.SequencedMap;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.metrics.Counter;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.metrics.MeanAccumulator;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.runtime.Cores;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.model.catalog.ComponentCatalog;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.component.database.CapacityMode;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.database.ReadFrom;
import io.archsim.model.component.database.Replication;
import io.archsim.model.document.RequestType;

/**
 * What a database adds to its part of a run's report: its CPU's utilization and ceiling,
 * its buffer cache, the waits it averages, its group commit, the series behind them, and
 * the behaviours its technology brings that are in effect.
 */
final class DatabaseReport {

	private static final Map<String, List<TechnologyTrait>> TRAITS = ComponentCatalog
		.technologyTraits(DatabaseSpec.TYPE);

	/** The waits a database averages per bucket, in the report's order. */
	private static final List<String> WAITS = List.of(DatabaseCounters.LOCK_WAIT_MS, DatabaseCounters.COMMIT_WAIT_MS,
			DatabaseCounters.TICKET_WAIT_MS, DatabaseCounters.REPLICA_ACK_MS);

	private static final double MS_PER_SECOND = 1000.0;

	private static final int DIGITS = 4;

	private static final int BATCH_DIGITS = 3;

	private DatabaseReport() {
	}

	/**
	 * The ceiling the database's CPU sets, at the speed of its machine's threads. With
	 * one instance, its cores busy for the CPU's share of the window's mean service time.
	 * With several, where its busiest instance's cores fill: the CPU work at full speed
	 * it was sent per request, the operations it refused costing what the ones it served
	 * did (ADR-0083).
	 * @param state the database
	 * @param serviceMs the mean service time, if any operation was served
	 * @return the ceiling in requests per second, when the database has a CPU size
	 */
	static SequencedMap<String, Double> resourceCapacity(DatabaseState state, OptionalDouble serviceMs) {
		SequencedMap<String, Double> ceilings = new LinkedHashMap<>();
		DatabaseConfig config = state.settings().config();
		Double cores = config.cpuCores();
		if (cores == null || serviceMs.orElse(0.0) == 0 || config.cpuShare() <= 0) {
			return ceilings;
		}
		double rate = state.metrics().arrivals().windowRate();
		double demand = busiestDemandMsPerSecond(state);
		double fullSpeed = (demand > 0) ? rate * cores * MS_PER_SECOND / demand
				: cores * MS_PER_SECOND / (serviceMs.getAsDouble() * config.cpuShare());
		ceilings.put("cpu", state.shards().first().cores().machine().capacity(fullSpeed, cores, rate));
		return ceilings;
	}

	/**
	 * The database's own summary figures, each when it is modelled: its cores'
	 * utilization, its buffer cache's hit ratio, its partitions when they are not its
	 * shards, its mean waits, the commits each log flush covered, and its replicas'
	 * highest lag.
	 * @param state the database
	 * @return the figures, in the report's order
	 */
	static SequencedMap<String, @Nullable Number> extraSummary(DatabaseState state) {
		SequencedMap<String, @Nullable Number> figures = new LinkedHashMap<>();
		DatabaseSettings settings = state.settings();
		Gauge busy = state.shards().coreBusy();
		int executors = state.shards().first().cores().executors();
		if (busy != null) {
			int instances = settings.partitions() * (1 + settings.config().readReplicas());
			figures.put("cpuCoreUtilization", Cores.utilization(busy, executors * instances).orElse(null));
		}
		state.shards()
			.busiest()
			.ifPresent((busiest) -> figures.put("busiestInstanceCpuUtilization",
					PythonRound.round(busiest.meanBusy() / executors, DIGITS)));
		settings.bufferHitRatio().ifPresent((hit) -> figures.put("bufferHitRatio", PythonRound.round(hit, DIGITS)));
		if (settings.partitions() != settings.config().shards()) {
			figures.put("partitions", settings.partitions());
		}
		putMeanWaits(state.metrics(), figures);
		state.controls()
			.flushers()
			.ifPresent((logs) -> figures.put("commitsPerFlush", commitsPerFlush(logs).orElse(null)));
		ReplicationPipeline replication = state.replication();
		if (replication != null) {
			figures.put("maxReplicationLagMs", replication.maxLagMs().orElse(null));
		}
		return figures;
	}

	/**
	 * The CPU work at full speed a second its busiest instance was sent inside the
	 * window, for a database of several instances with a CPU size (ADR-0083).
	 * @param state the database
	 * @return the work, in milliseconds a second; 0 for one instance or without a CPU
	 * size
	 */
	static double busiestDemandMsPerSecond(DatabaseState state) {
		return state.shards()
			.busiest()
			.map((busiest) -> busiest.demandMs() / state.shards().windowSeconds())
			.orElse(0.0);
	}

	/**
	 * The database's own series, each when it is modelled: its busy cores, its mean
	 * waits, its replicas' lag and stale reads, its disk reads, the capacity units it
	 * consumed, and its refusals by its throughput limit or its units.
	 * @param state the database
	 * @return the series over the whole timeline, in the report's order
	 */
	static SequencedMap<String, List<@Nullable Double>> extraTimeseries(DatabaseState state) {
		SequencedMap<String, List<@Nullable Double>> series = new LinkedHashMap<>();
		NodeMetrics metrics = state.metrics();
		Gauge busy = state.shards().coreBusy();
		if (busy != null) {
			series.put("cpuBusyCores", Cores.busyCores(busy, state.shards().first().cores().speed()));
		}
		for (String wait : WAITS) {
			MeanAccumulator means = metrics.means().get(wait);
			if (means != null) {
				series.put(wait, means.series());
			}
		}
		ReplicationPipeline replication = state.replication();
		if (replication != null) {
			series.put("replicationLagMs", replication.lagSeries());
		}
		putRate(series, "staleReadRps", metrics.counters().get(DatabaseCounters.STALE_READS));
		putRate(series, "diskReadRps", metrics.counters().get(DatabaseCounters.DISK_READS));
		if (state.settings().config().metered()) {
			series.put("consumedReadUnits", CapacityUnits.consumedPerSecond(metrics, RequestType.READ));
			series.put("consumedWriteUnits", CapacityUnits.consumedPerSecond(metrics, RequestType.WRITE));
		}
		putRate(series, "throttledRps", metrics.rejected().get(FailureReason.THROTTLED));
		return series;
	}

	/**
	 * The behaviours the database's technology brings that are in effect.
	 * @param settings the database's settings in effect
	 * @return the traits in effect, in the technology's order
	 */
	static List<TechnologyTrait> behaviour(DatabaseSettings settings) {
		DatabaseConfig config = settings.config();
		Replication replication = settings.replication();
		boolean replicas = config.readReplicas() > 0;
		boolean metered = config.metered();
		Map<String, Boolean> active = Map.ofEntries(Map.entry("row_locks", settings.locksRows()),
				Map.entry("document_locks", settings.locksRows()),
				Map.entry("mvcc_reads", !settings.readersWaitForWriters()),
				Map.entry("locking_reads", settings.readersWaitForWriters()),
				Map.entry("group_commit", config.flushesCommits()),
				Map.entry("async_replicas", replication == Replication.ASYNC),
				Map.entry("parallel_replica", replication == Replication.ASYNC),
				Map.entry("majority_writes", replication == Replication.MAJORITY && replicas),
				Map.entry("sync_replicas", replication == Replication.ALL && replicas),
				Map.entry("primary_reads", settings.readFrom() == ReadFrom.PRIMARY),
				Map.entry("tickets", settings.readTickets().isPresent()),
				Map.entry("mongos", settings.partitions() > 1), Map.entry("fan_out", settings.scatterShare() > 0),
				Map.entry("partition_limits", metered), Map.entry("capacity_units", metered),
				Map.entry("burst", metered && config.capacityMode() == CapacityMode.PROVISIONED),
				Map.entry("read_amplification", settings.sstablesPerRead() > 1));
		return TRAITS.getOrDefault(config.technology().json(), List.of())
			.stream()
			.filter((trait) -> active.getOrDefault(trait.id(), true))
			.toList();
	}

	// the waits averaged in the window, each it recorded
	private static void putMeanWaits(NodeMetrics metrics, SequencedMap<String, @Nullable Number> figures) {
		for (String wait : WAITS) {
			MeanAccumulator means = metrics.means().get(wait);
			if (means != null && means.windowMean().isPresent()) {
				figures.put("mean" + Character.toUpperCase(wait.charAt(0)) + wait.substring(1),
						PythonRound.round(means.windowMean().getAsDouble(), DIGITS));
			}
		}
	}

	// a count's rate per second in each bucket, when the database counted it
	private static void putRate(SequencedMap<String, List<@Nullable Double>> series, String name,
			@Nullable Counter counter) {
		if (counter != null) {
			series.put(name, RuntimeComponent.series(counter.rateSeries()));
		}
	}

	/**
	 * The commits each log flush covered over the run.
	 * @param logs the shards' logs
	 * @return the commits per flush, rounded; empty before any flush
	 */
	static Optional<Double> commitsPerFlush(List<LogFlusher> logs) {
		long flushes = logs.stream().mapToLong(LogFlusher::flushes).sum();
		long commits = logs.stream().mapToLong(LogFlusher::commits).sum();
		return (flushes > 0) ? Optional.of(PythonRound.round((double) commits / flushes, BATCH_DIGITS))
				: Optional.empty();
	}

}
