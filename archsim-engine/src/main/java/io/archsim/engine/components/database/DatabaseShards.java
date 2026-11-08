package io.archsim.engine.components.database;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Resource;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.runtime.Cores;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.SimContext;
import io.archsim.engine.runtime.WorkerPool;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.database.ReadFrom;
import io.archsim.model.component.database.Replication;
import io.archsim.model.document.RequestType;

/**
 * A database's shards, each a primary and its read replicas, every instance with its own
 * connections, CPU and tickets. A write goes to its shard's primary; a read to the
 * primary, to the replicas in turn, or to every instance in turn, as the database reads.
 */
final class DatabaseShards {

	private final Simulation simulation;

	private final DatabaseConfig config;

	private final List<List<DatabaseInstance>> groups;

	private final ReadFrom readFrom;

	private final int[] turns;

	private final @Nullable Gauge coreBusy;

	/**
	 * A database's shards at the start of a run.
	 * @param context the run
	 * @param settings the database's settings in effect
	 * @param metrics its metrics, which its instances' queues count in
	 */
	DatabaseShards(SimContext context, DatabaseSettings settings, NodeMetrics metrics) {
		this.simulation = context.simulation();
		this.config = settings.config();
		this.readFrom = settings.readFrom();
		this.turns = new int[settings.partitions()];
		this.coreBusy = (this.config.cpuCores() != null) ? new Gauge(context.timeline()) : null;
		WorkerPool.Queue queue = new WorkerPool.Queue(this.config.queueCapacity(), this.config.queueTimeoutMs());
		boolean replicates = settings.replication() != Replication.NONE;
		List<List<DatabaseInstance>> built = new ArrayList<>(settings.partitions());
		for (int shard = 0; shard < settings.partitions(); shard++) {
			List<DatabaseInstance> group = new ArrayList<>();
			for (int index = 0; index <= this.config.readReplicas(); index++) {
				ReplicaLog log = (index > 0 && replicates) ? new ReplicaLog(context.simulation()) : null;
				WorkerPool pool = new WorkerPool(context.simulation(), this.config.maxConnections(), queue, metrics);
				Cores cores = new Cores(context.simulation(), this.config.cpuCores(), this.config.maxConnections(),
						new Cores.Accounts(this.coreBusy,
								context.machineFor(this.config.cpuCores(), this.config.siblingSpeed())));
				group.add(new DatabaseInstance(index, pool, cores, tickets(context, settings.readTickets()),
						tickets(context, settings.writeTickets()), log));
			}
			built.add(List.copyOf(group));
		}
		this.groups = List.copyOf(built);
	}

	/**
	 * The instance of a shard that serves an operation: a write's primary; a read's
	 * primary, next replica, or next instance, as the database reads.
	 * @param shard the shard
	 * @param operation a read or a write
	 * @return the instance
	 */
	DatabaseInstance pick(int shard, RequestType operation) {
		List<DatabaseInstance> group = this.groups.get(shard);
		if (operation == RequestType.WRITE || group.size() == 1 || this.readFrom == ReadFrom.PRIMARY) {
			return group.getFirst();
		}
		int turn = this.turns[shard];
		if (this.readFrom == ReadFrom.REPLICAS) {
			int replica = turn % (group.size() - 1);
			this.turns[shard] = replica + 1;
			return group.get(1 + replica);
		}
		this.turns[shard] = (turn + 1) % group.size();
		return group.get(turn);
	}

	/**
	 * Works on an instance for a time: the CPU's share of it on the instance's cores,
	 * waiting for a free one, the rest off them.
	 * @param instance the instance
	 * @param timeMs the time, in milliseconds
	 * @param next what to do once it is done
	 */
	void work(DatabaseInstance instance, double timeMs, Runnable next) {
		if (this.config.cpuCores() == null) {
			this.simulation.after(timeMs, next);
			return;
		}
		double cpuMs = timeMs * this.config.cpuShare();
		instance.cores().run(cpuMs, () -> this.simulation.after(timeMs - cpuMs, next));
	}

	/**
	 * A shard's read replicas.
	 * @param shard the shard
	 * @return its instances but the primary
	 */
	List<DatabaseInstance> replicas(int shard) {
		List<DatabaseInstance> group = this.groups.get(shard);
		return group.subList(1, group.size());
	}

	/**
	 * Every instance, shard by shard, each shard's primary first.
	 * @return the instances
	 */
	List<DatabaseInstance> all() {
		return this.groups.stream().flatMap(List::stream).toList();
	}

	/**
	 * The first shard's primary, whose CPU every instance's is like.
	 * @return the instance
	 */
	DatabaseInstance first() {
		return this.groups.getFirst().getFirst();
	}

	/**
	 * The instances' busy CPU executors, when the database has a CPU size.
	 * @return the gauge, or {@code null} without a CPU size
	 */
	@Nullable Gauge coreBusy() {
		return this.coreBusy;
	}

	/**
	 * Ends the busy executors' series at the end of the run.
	 * @param end when the run stopped
	 */
	void finish(double end) {
		if (this.coreBusy != null) {
			this.coreBusy.finish(end);
		}
	}

	// tickets that limit an instance's reads or writes, when it has them
	private static @Nullable Resource tickets(SimContext context, OptionalInt count) {
		return count.isPresent() ? new Resource(context.simulation(), count.getAsInt()) : null;
	}

}
