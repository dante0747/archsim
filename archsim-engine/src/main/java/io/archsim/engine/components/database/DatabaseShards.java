package io.archsim.engine.components.database;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Resource;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.metrics.Timeline;
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

	private static final double MS_PER_SECOND = 1000.0;

	private final Simulation simulation;

	private final DatabaseConfig config;

	private final List<List<DatabaseInstance>> groups;

	private final ReadFrom readFrom;

	private final int[] turns;

	private final @Nullable Gauge coreBusy;

	private final Timeline timeline;

	/** What each instance did, for a database of several with a CPU size; else none. */
	private final List<InstanceLoad> loads;

	/**
	 * A database's shards at the start of a run.
	 * @param context the run
	 * @param settings the database's settings in effect
	 * @param metrics its metrics, which its instances' queues count in
	 */
	DatabaseShards(SimContext context, DatabaseSettings settings, NodeMetrics metrics) {
		this.simulation = context.simulation();
		this.timeline = context.timeline();
		this.config = settings.config();
		this.readFrom = settings.readFrom();
		this.turns = new int[settings.partitions()];
		this.coreBusy = (this.config.cpuCores() != null) ? new Gauge(context.timeline()) : null;
		boolean several = settings.partitions() * (1 + this.config.readReplicas()) > 1;
		InstanceParts parts = new InstanceParts(context, settings, metrics,
				new WorkerPool.Queue(this.config.queueCapacity(), this.config.queueTimeoutMs()),
				several && this.coreBusy != null);
		List<List<DatabaseInstance>> built = new ArrayList<>(settings.partitions());
		for (int shard = 0; shard < settings.partitions(); shard++) {
			List<DatabaseInstance> group = new ArrayList<>();
			for (int index = 0; index <= this.config.readReplicas(); index++) {
				group.add(newInstance(parts, index));
			}
			built.add(List.copyOf(group));
		}
		this.groups = List.copyOf(built);
		this.loads = all().stream().map(DatabaseInstance::load).filter(Objects::nonNull).toList();
	}

	/**
	 * The instance of a shard that serves an operation: a write's primary; a read's
	 * primary, next replica, or next instance, as the database reads.
	 * @param shard the shard
	 * @param operation a read or a write
	 * @return the instance
	 */
	DatabaseInstance pick(int shard, RequestType operation) {
		DatabaseInstance picked = route(shard, operation);
		InstanceLoad load = picked.load();
		if (load != null) {
			load.sent(this.simulation.now());
		}
		return picked;
	}

	private DatabaseInstance route(int shard, RequestType operation) {
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
	 * Serves an operation on an instance for a time: the CPU's share of it on the
	 * instance's cores, waiting for a free one, the rest off them.
	 * @param instance the instance
	 * @param timeMs the time, in milliseconds
	 * @param next what to do once it is done
	 */
	void serve(DatabaseInstance instance, double timeMs, Runnable next) {
		InstanceLoad load = instance.load();
		if (load != null) {
			load.served(this.simulation.now(), timeMs * this.config.cpuShare());
		}
		work(instance, timeMs, next);
	}

	/**
	 * Applies a primary's write on a replica for a time, as {@link #serve} works.
	 * @param replica the replica
	 * @param timeMs the time, in milliseconds
	 * @param next what to do once it is done
	 */
	void apply(DatabaseInstance replica, double timeMs, Runnable next) {
		InstanceLoad load = replica.load();
		if (load != null) {
			load.applied(this.simulation.now(), timeMs * this.config.cpuShare());
		}
		work(replica, timeMs, next);
	}

	private void work(DatabaseInstance instance, double timeMs, Runnable next) {
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
		this.loads.forEach((load) -> load.busy().finish(end));
	}

	/**
	 * Its busiest instance: the one sent the most CPU work inside the measurement window,
	 * for a database of several instances with a CPU size (ADR-0083).
	 * @return what that instance did; empty for one instance or without a CPU size
	 */
	Optional<InstanceLoad> busiest() {
		return this.loads.stream().max(Comparator.comparingDouble(InstanceLoad::demandMs));
	}

	/**
	 * The window's length.
	 * @return it, in seconds
	 */
	double windowSeconds() {
		return this.timeline.windowMs() / MS_PER_SECOND;
	}

	// one instance of a shard: its connections, its CPU, its tickets, a replica's log
	private DatabaseInstance newInstance(InstanceParts parts, int index) {
		SimContext context = parts.context();
		ReplicaLog log = (index > 0 && parts.settings().replication() != Replication.NONE)
				? new ReplicaLog(context.simulation()) : null;
		WorkerPool pool = new WorkerPool(context.simulation(), this.config.maxConnections(), parts.queue(),
				parts.metrics());
		InstanceLoad load = parts.watched() ? new InstanceLoad(context.timeline()) : null;
		Cores cores = new Cores(context.simulation(), this.config.cpuCores(), this.config.maxConnections(),
				new Cores.Accounts(this.coreBusy, (load != null) ? load.busy() : null,
						context.machineFor(this.config.cpuCores(), this.config.siblingSpeed())));
		return new DatabaseInstance(index, pool, cores, tickets(context, parts.settings().readTickets()),
				tickets(context, parts.settings().writeTickets()), log, load);
	}

	// tickets that limit an instance's reads or writes, when it has them
	private static @Nullable Resource tickets(SimContext context, OptionalInt count) {
		return count.isPresent() ? new Resource(context.simulation(), count.getAsInt()) : null;
	}

	/**
	 * What every instance of a database is built from.
	 *
	 * @param context the run
	 * @param settings the database's settings in effect
	 * @param metrics its metrics, which its instances' queues count in
	 * @param queue the wait queue its instances' connections share
	 * @param watched whether each instance's load is watched: a database of several
	 * instances with a CPU size
	 */
	private record InstanceParts(SimContext context, DatabaseSettings settings, NodeMetrics metrics,
			WorkerPool.Queue queue, boolean watched) {
	}

}
