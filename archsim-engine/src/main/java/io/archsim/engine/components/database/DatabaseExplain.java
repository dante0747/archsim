package io.archsim.engine.components.database;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.queueing.Zipf;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.text.NumberText;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.document.Workload;
import io.archsim.model.text.PythonFormat;

/**
 * The calculations behind a database's own figures, each when it applies: its buffer
 * cache's hit ratio, its hottest row's lock, its group commit, its replicas' and its
 * capacity units' ({@link DatabaseReplicaExplain}), and its search fan-out.
 */
final class DatabaseExplain {

	private static final double MS_PER_SECOND = 1000.0;

	/** A commit waits for the flush in progress and its own: about one and a half. */
	private static final double FLUSHES_PER_COMMIT = 1.5;

	private static final int TWO_DIGITS = 2;

	private DatabaseExplain() {
	}

	/**
	 * A database's formulas, in the report's order.
	 * @param state the database
	 * @param workload the run's workload, its keyspace
	 * @param windowMs the measurement window's length
	 * @param summary the database's summary in the report
	 * @return the formulas that apply
	 */
	static List<Formula> of(DatabaseState state, Workload workload, double windowMs, NodeSummary summary) {
		List<Formula> formulas = new ArrayList<>();
		DatabaseSettings settings = state.settings();
		DatabaseConfig config = settings.config();
		Double workingSetGib = config.workingSetGib();
		Double memoryGib = config.memoryGib();
		if (workingSetGib != null && memoryGib != null) {
			formulas.add(bufferHit(settings, memoryGib, workingSetGib));
		}
		double windowS = windowMs / MS_PER_SECOND;
		double writesPerSecond = (windowS != 0) ? summary.counter(DatabaseCounters.WRITES) / windowS : 0.0;
		if (settings.locksRows() && writesPerSecond > 0) {
			formulas.add(hotRow(config, workload, writesPerSecond));
		}
		Optional<Double> commitsPerFlush = state.controls()
			.flushers()
			.flatMap(DatabaseReport::commitsPerFlush)
			.filter((commits) -> commits != 0);
		commitsPerFlush.ifPresent((commits) -> formulas.add(groupCommit(config, commits)));
		formulas.addAll(DatabaseReplicaExplain.of(settings, writesPerSecond));
		if (settings.scatterShare() > 0) {
			formulas.add(fanOut(settings, summary));
		}
		return formulas;
	}

	private static Formula bufferHit(DatabaseSettings settings, double memoryGib, double workingSetGib) {
		String share = PythonFormat.format(DatabaseConfig.BUFFER_CACHE_SHARE, "g");
		String note = (settings.sstablesPerRead() > 1)
				? " for each of about " + NumberText.formula(settings.sstablesPerRead(), 1) + " SSTables." : ".";
		return Formula
			.of("buffer_hit", "Buffer cache hit ratio", "hit = min(1, " + share + " × memory ÷ (working set ÷ shards))",
					"min(1, " + share + " × " + NumberText.formula(memoryGib, 1) + " GiB ÷ ("
							+ NumberText.formula(workingSetGib, 1) + " GiB ÷ " + settings.partitions() + "))")
			.withResult(settings.bufferHitRatio().orElseThrow(), "")
			.withNote("A read that misses pays a disk read" + note)
			.withMetric("diskReadRps");
	}

	private static Formula hotRow(DatabaseConfig config, Workload workload, double writesPerSecond) {
		double share = Zipf.share(workload.keyCount(), workload.keySkew(), 1);
		double writeMs = config.writeServiceTimeMs().expected();
		double flushMs = config.flushesCommits() ? config.commitFlushMs().expected() * FLUSHES_PER_COMMIT : 0.0;
		double busy = writesPerSecond * share * (writeMs + flushMs) / MS_PER_SECOND;
		return Formula
			.of("hot_row", "Hottest row's lock",
					"lock busy = writes/s × hottest key's share × (write time + commit wait) ÷ 1000",
					NumberText.formula(writesPerSecond, TWO_DIGITS) + " × " + NumberText.percent(share) + " × ("
							+ NumberText.formula(writeMs, TWO_DIGITS) + " + " + NumberText.formula(flushMs, TWO_DIGITS)
							+ ") ms ÷ 1000")
			.withResult(busy, "")
			.withNote("The hottest of " + PythonFormat.format(workload.keyCount(), ",") + " keys gets "
					+ NumberText.percent(share) + " of writes (Zipf s = " + PythonFormat.format(workload.keySkew(), "g")
					+ "). As its lock nears 100% busy, writes to it queue and hold connections while they wait.")
			.withMetric(DatabaseCounters.LOCK_WAIT_MS);
	}

	private static Formula groupCommit(DatabaseConfig config, double commitsPerFlush) {
		return Formula
			.of("group_commit", "Group commit", "commits per flush = commits ÷ log flushes",
					"over the run, with a mean flush of "
							+ NumberText.formula(config.commitFlushMs().expected(), TWO_DIGITS) + " ms")
			.withResult(commitsPerFlush, "commits")
			.withNote("A commit waits for the flush in progress plus its own, about 1.5 flushes on average; "
					+ "batching keeps write throughput from being capped at 1000 ÷ flush time per second.")
			.withMetric(DatabaseCounters.COMMIT_WAIT_MS);
	}

	private static Formula fanOut(DatabaseSettings settings, NodeSummary summary) {
		Double arrivalRps = summary.arrivalRps();
		double share = settings.scatterShare();
		return Formula
			.of("fan_out", "Search fan-out", "shard queries per second = reads/s × share scattered × shards",
					NumberText.formula(arrivalRps, TWO_DIGITS) + " × " + NumberText.percent(share) + " × "
							+ settings.partitions())
			.withResult(((arrivalRps != null) ? arrivalRps : 0.0) * share * settings.partitions(), "queries/s")
			.withNote("A scattered read finishes when the slowest shard answers, so its latency is the maximum of "
					+ "the shards' times.");
	}

}
