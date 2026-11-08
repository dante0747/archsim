package io.archsim.engine.components.database;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.text.NumberText;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.database.Replication;

/**
 * The calculations behind a database's replicas and its capacity units, each when it
 * applies: how fast asynchronous replicas replay writes, how many replicas a write waits
 * for, and a metered table's partitions and units per request.
 */
final class DatabaseReplicaExplain {

	private static final double MS_PER_SECOND = 1000.0;

	private static final int TWO_DIGITS = 2;

	private DatabaseReplicaExplain() {
	}

	/**
	 * A database's replicas' and capacity units' formulas, in the report's order.
	 * @param settings the database's settings in effect
	 * @param writesPerSecond the writes it served per second in the window
	 * @return the formulas that apply
	 */
	static List<Formula> of(DatabaseSettings settings, double writesPerSecond) {
		List<Formula> formulas = new ArrayList<>();
		DatabaseConfig config = settings.config();
		if (settings.replication() == Replication.ASYNC) {
			formulas.add(replayCapacity(settings, writesPerSecond));
		}
		if (settings.replicaAcks() > 0) {
			formulas.add(replicaAcks(settings));
		}
		if (config.metered()) {
			formulas.add(partitions(settings));
			formulas.add(unitCost(config));
		}
		return formulas;
	}

	private static Formula replayCapacity(DatabaseSettings settings, double writesPerSecond) {
		DatabaseConfig config = settings.config();
		double writeMs = config.writeServiceTimeMs().expected();
		double applyMs = writeMs * config.replicaApplyFactor();
		double perShard = writesPerSecond / settings.partitions();
		@Nullable Double capacity = (applyMs != 0) ? settings.applyWorkers() * MS_PER_SECOND / applyMs : null;
		return Formula
			.of("replay_capacity", "Replica replay capacity",
					"replay capacity = apply workers × 1000 ÷ replay time per write",
					settings.applyWorkers() + " × 1000 ÷ (" + NumberText.formula(writeMs, TWO_DIGITS) + " ms × "
							+ NumberText.formula(config.replicaApplyFactor(), TWO_DIGITS) + ")")
			.withResult(capacity, "writes/s")
			.withNote("Writes per shard: " + NumberText.formula(perShard, TWO_DIGITS)
					+ "/s. When writes outpace replay the lag grows without bound and replica reads turn stale.")
			.withMetric("replicationLagMs");
	}

	private static Formula replicaAcks(DatabaseSettings settings) {
		boolean majority = settings.replication() == Replication.MAJORITY;
		return Formula
			.of("replica_acks", "Write acknowledgement",
					majority ? "acks needed = majority of (1 + replicas) − 1" : "acks needed = replicas",
					"1 + " + settings.config().readReplicas() + " members")
			.withResult((double) settings.replicaAcks(), "replicas")
			.withNote("Each write also waits for that many replicas to replay it, plus the network round trip.")
			.withMetric(DatabaseCounters.REPLICA_ACK_MS);
	}

	private static Formula partitions(DatabaseSettings settings) {
		DatabaseConfig config = settings.config();
		Double dataSizeGib = config.dataSizeGib();
		return Formula
			.of("partitions", "Partitions", "partitions = max(⌈RCU ÷ 3000 + WCU ÷ 1000⌉, ⌈size ÷ 10 GB⌉)",
					"max(⌈" + NumberText.formula(config.readUnitsPerSecond(), 1) + " ÷ 3000 + "
							+ NumberText.formula(config.writeUnitsPerSecond(), 1) + " ÷ 1000⌉, ⌈"
							+ NumberText.formula((dataSizeGib != null) ? dataSizeGib : 0.0, 1) + " ÷ 10⌉)")
			.withResult((double) settings.partitions(), "partitions")
			.withNote("Each partition serves at most 3,000 read and 1,000 write units per second, whatever the "
					+ "table's total.")
			.withMetric("throttledRps");
	}

	private static Formula unitCost(DatabaseConfig config) {
		return Formula
			.of("unit_cost", "Capacity units per request",
					"read = ⌈item ÷ 4 KB⌉ × (½ if eventually consistent),  write = ⌈item ÷ 1 KB⌉",
					"item " + NumberText.formula(config.itemSizeKb(), 1) + " KB, " + config.consistentReads().json()
							+ " reads")
			.withResult(config.unitsPerRead(), "read units")
			.withNote("Each write costs " + NumberText.formula(config.unitsPerWrite(), 1) + " write unit(s).")
			.withMetric("consumedReadUnits");
	}

}
