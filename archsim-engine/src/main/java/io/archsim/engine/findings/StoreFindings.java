package io.archsim.engine.findings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.report.ReportNode;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.document.Workload;

/**
 * Findings about a data store: a shard far busier than its fair share, how much of the
 * read load replicas absorbed, replicas falling behind, and writes queuing for row locks.
 */
final class StoreFindings {

	/** A shard is hot above this times its fair share... */
	private static final double HOT_SHARD_RATIO = 1.5;

	/**
	 * ... or above this times it, when it is also at least {@link #HOT_SHARD_BUSY} busy.
	 */
	private static final double HOT_SHARD_BUSY_RATIO = 1.25;

	private static final double HOT_SHARD_BUSY = 0.85;

	/** Replicas this far behind serve noticeably stale reads, in milliseconds. */
	private static final double LAG_WARN_MS = 1000;

	/** A mean lock wait under this share of a write's own time is not named. */
	private static final double LOCK_WARN_SHARE = 0.25;

	/** Above this share of writes, read replicas add little. */
	private static final double WRITE_HEAVY = 0.5;

	private static final String SPREAD_KEYS = "Pick a partition key with more even popularity, cache the "
			+ "hottest keys in front of the database, or split hot keys (for "
			+ "example with a suffix). More shards do not help a single hot key.";

	private static final String REPLICAS_ADD_LITTLE = "Writes are most of the load, so replicas add little: shard "
			+ "the data or make writes cheaper.";

	private static final String REPLICAS_GO_STALE = "Replica reads can be stale while replicas replay the primary's "
			+ "writes (see replication lag).";

	private StoreFindings() {
	}

	/**
	 * A shard that gets far more than its fair share of a sharded store's requests.
	 * @param node the store
	 * @param config its settings
	 * @param workload the design's workload, for its keys' popularity
	 * @return the finding, when a shard gets at least 1.5 times its fair share, or 1.25
	 * times it and is estimated at least 85% busy; none for a store of one shard
	 */
	static Optional<RunFinding> hotShard(ReportNode node, DatabaseConfig config, Workload workload) {
		if (config.shards() < 2) {
			return Optional.empty();
		}
		Shards shards = Shards.of(node.summary(), config.shards());
		if (shards.total() < DataFindings.MIN_SAMPLES || shards.ratio() < HOT_SHARD_BUSY_RATIO) {
			return Optional.empty();
		}
		ShardLoad load = ShardLoad.of(node, config, shards.share());
		Double estimate = load.estimate();
		if (shards.ratio() < HOT_SHARD_RATIO && (estimate == null || estimate < HOT_SHARD_BUSY)) {
			return Optional.empty();
		}
		String detail = "With " + config.shards() + " shards each should get about "
				+ FindingText.percent(shards.fair()) + " of requests, but key popularity (Zipf s="
				+ FindingText.general(workload.keySkew()) + " over " + FindingText.count(workload.keyCount())
				+ " keys) sends " + FindingText.percent(shards.share()) + " to shard " + shards.hot() + ", "
				+ FindingText.tenths(shards.ratio()) + "x its fair share." + load.text();
		return Optional.of(new RunFinding(
				(estimate != null && estimate >= Classification.SATURATED) ? FindingSeverity.CRITICAL
						: FindingSeverity.WARNING,
				FindingKind.HOT_SHARD, node.id(), "Hot shard: " + node.label() + " shard " + shards.hot() + " gets "
						+ FindingText.percent(shards.share()) + " of requests",
				detail, SPREAD_KEYS, shards.evidence(estimate)));
	}

	/**
	 * How much of a store's read load its replicas absorbed.
	 * @param node the store
	 * @param config its settings
	 * @return the finding, for a store with read replicas and enough reads
	 */
	static Optional<RunFinding> readReplicas(ReportNode node, DatabaseConfig config) {
		int replicas = config.readReplicas();
		NodeSummary summary = node.summary();
		long reads = summary.counter("reads");
		if (replicas < 1 || reads < DataFindings.MIN_SAMPLES) {
			return Optional.empty();
		}
		long writes = summary.counter("writes");
		long replicaReads = summary.counter("replicaReads");
		double share = (double) replicaReads / reads;
		double writeShare = (double) writes / (reads + writes);
		return Optional.of(new RunFinding(FindingSeverity.INFO, FindingKind.READ_REPLICAS, node.id(),
				node.label() + " read replicas served " + FindingText.percent(share) + " of reads",
				replicas + " replica" + FindingText.plural(replicas) + " per shard took "
						+ FindingText.count(replicaReads) + " of " + FindingText.count(reads) + " reads; all "
						+ FindingText.count(writes) + " writes still went to the primary.",
				(writeShare > WRITE_HEAVY) ? REPLICAS_ADD_LITTLE : REPLICAS_GO_STALE,
				RunFinding.emptyEvidence()
					.put("replicaReads", replicaReads)
					.put("reads", reads)
					.put("writes", writes)
					.put("share", round(share))));
	}

	/**
	 * Replicas falling behind the primary.
	 * @param node the store
	 * @param applyWorkers the threads its replicas replay writes with, if set
	 * @return the finding, when its replicas fell at least a second behind
	 */
	static Optional<RunFinding> replicationLag(ReportNode node, @Nullable Integer applyWorkers) {
		NodeSummary summary = node.summary();
		Double lag = summary.maxReplicationLagMs();
		if (lag == null || lag < LAG_WARN_MS) {
			return Optional.empty();
		}
		long stale = summary.counter("staleReads");
		long replicaReads = summary.counter("replicaReads");
		long writes = summary.counter("writes");
		String behind = FindingText.tenths(lag / FindingText.MS_PER_SECOND);
		String detail = "Replicas replay the primary's writes; they fell up to " + behind + " s behind. "
				+ FindingText.count(writes) + " writes in the window had to be replayed"
				+ ((replicaReads != 0) ? ", and " + FindingText.count(stale) + " of " + FindingText.count(replicaReads)
						+ " replica reads returned stale data." : ".");
		return Optional.of(new RunFinding(FindingSeverity.WARNING, FindingKind.REPLICATION_LAG, node.id(),
				node.label() + " replicas lag up to " + behind + " s behind the primary", detail,
				"Writes outpace replay. Make writes cheaper, shard the writes, use a database with parallel replay"
						+ ((applyWorkers != null) ? "" : " (or raise replica apply workers)")
						+ ", or read your own writes from the primary.",
				RunFinding.emptyEvidence()
					.put("maxLagMs", lag)
					.put("staleReads", stale)
					.put("replicaReads", replicaReads)
					.put("writes", writes)));
	}

	/**
	 * Writes queuing for row locks on hot keys.
	 * @param node the store
	 * @param workload the design's workload, for its keys' popularity
	 * @return the finding, when enough writes waited for a lock at least a quarter of a
	 * write's own time on average
	 */
	static Optional<RunFinding> lockContention(ReportNode node, Workload workload) {
		NodeSummary summary = node.summary();
		Double wait = summary.meanLockWaitMs();
		double service = Objects.requireNonNullElse(summary.meanServiceMs(), 0.0);
		long writes = summary.counter("writes");
		if (wait == null || writes < DataFindings.MIN_SAMPLES || wait <= 0) {
			return Optional.empty();
		}
		if (service != 0 && wait < LOCK_WARN_SHARE * service) {
			return Optional.empty();
		}
		long waits = summary.counter("lockWaits");
		return Optional.of(new RunFinding((wait >= service) ? FindingSeverity.WARNING : FindingSeverity.INFO,
				FindingKind.LOCK_CONTENTION, node.id(),
				"Writes to " + node.label() + " wait " + FindingText.tenths(wait) + " ms on average for row locks",
				FindingText.count(waits) + " of " + FindingText.count(writes) + " writes found their row locked by "
						+ "another write. Key popularity (Zipf s=" + FindingText.general(workload.keySkew()) + " over "
						+ FindingText.count(workload.keyCount()) + " keys) concentrates writes on a few hot rows, and "
						+ "a waiting write holds its connection.",
				"Spread hot rows (shard counters, append instead of update), shorten transactions, or batch updates to "
						+ "the hottest keys.",
				RunFinding.emptyEvidence().put("meanLockWaitMs", wait).put("lockWaits", waits).put("writes", writes)));
	}

	private static double round(double share) {
		return PythonRound.round(share, DataFindings.SHARE_DECIMALS);
	}

	/**
	 * How a sharded store's keyed operations spread over its shards.
	 *
	 * @param counts the operations each shard served; a scattered read, which runs on
	 * every shard but is counted once on shard 0, is left out, since it loads the shards
	 * evenly
	 * @param total the operations
	 * @param hot the busiest shard, the first of the busiest
	 */
	private record Shards(List<Long> counts, long total, int hot) {

		static Shards of(NodeSummary summary, int shards) {
			List<Long> counts = new ArrayList<>();
			for (int i = 0; i < shards; i++) {
				counts.add(summary.counter("shard" + i));
			}
			counts.set(0, Math.max(0, counts.getFirst() - summary.counter("scatterReads")));
			int hot = 0;
			for (int i = 1; i < shards; i++) {
				if (counts.get(i) > counts.get(hot)) {
					hot = i;
				}
			}
			return new Shards(List.copyOf(counts), counts.stream().mapToLong(Long::longValue).sum(), hot);
		}

		double share() {
			return (double) this.counts.get(this.hot) / this.total;
		}

		double fair() {
			return 1.0 / this.counts.size();
		}

		double ratio() {
			return (this.total == 0) ? 0 : share() / fair();
		}

		ObjectNode evidence(@Nullable Double estimate) {
			ArrayNode shares = JsonNodeFactory.instance.arrayNode();
			this.counts.forEach((count) -> shares.add(round((double) count / this.total)));
			ObjectNode evidence = RunFinding.emptyEvidence();
			evidence.set("shardShares", shares);
			return evidence.put("hotShard", this.hot)
				.put("share", round(share()))
				.put("fairShare", round(fair()))
				.put("estimatedHotShardUtilization", (estimate != null) ? round(estimate) : null);
		}

	}

	/**
	 * How busy a hot shard is estimated to be: by its connections, or by its CPU cores
	 * when they are set and busier.
	 *
	 * @param estimate the estimated utilization, if it can be estimated
	 * @param utilization the whole store's matching utilization, if measured
	 */
	private record ShardLoad(@Nullable Double estimate, @Nullable Double utilization) {

		static ShardLoad of(ReportNode node, DatabaseConfig config, double share) {
			NodeSummary summary = node.summary();
			// the instances serving a shard: the primary alone when reads go to it,
			// else the primary and its replicas
			int serving = node.behaves("primary_reads") ? 1 : 1 + config.readReplicas();
			double hold = Objects.requireNonNullElse(summary.meanHoldMs(), 0.0);
			double rate = Objects.requireNonNullElse(summary.arrivalRps(), 0.0);
			Double estimate = (hold != 0 && rate != 0)
					? (rate * share * hold / FindingText.MS_PER_SECOND) / (serving * config.maxConnections()) : null;
			ShardLoad byConnections = new ShardLoad(estimate, summary.utilization());
			double cores = Objects.requireNonNullElse(config.cpuCores(), 0.0);
			double service = Objects.requireNonNullElse(summary.meanServiceMs(), 0.0);
			if (cores == 0 || config.cpuShare() == 0 || service == 0 || rate == 0) {
				return byConnections;
			}
			// with CPU cores set, a shard usually runs out of CPU before connections
			double byCpu = rate * share * service * config.cpuShare() / (FindingText.MS_PER_SECOND * cores * serving);
			return (estimate == null || byCpu > estimate) ? new ShardLoad(byCpu, summary.cpuCoreUtilization())
					: byConnections;
		}

		// How busy the hot shard is estimated to be, in words; nothing without one.
		String text() {
			if (this.estimate == null) {
				return "";
			}
			return " That shard is an estimated " + FindingText.percent(this.estimate) + " busy"
					+ ((this.utilization != null)
							? " while the whole cluster averages " + FindingText.percent(this.utilization) + "." : ".");
		}

	}

}
