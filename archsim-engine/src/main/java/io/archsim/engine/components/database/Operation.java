package io.archsim.engine.components.database;

import java.util.Optional;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Resource;
import io.archsim.engine.kernel.Signal;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.SpanKind;
import io.archsim.engine.runtime.WorkerPool;
import io.archsim.model.document.RequestType;

/**
 * One operation of a request on one shard of a database: past the shard's throughput
 * limit, on a connection of the instance that serves it, with a ticket, past a row's lock
 * (a write's own, or a reader waiting for a writer's), its time on the instance's CPU and
 * off it, disk reads when a read misses the buffer cache, then, for a write, the log's
 * flush. A scatter's operations on its other shards leave the database's figures out.
 */
final class Operation {

	private final DatabaseState state;

	private final Request request;

	private final int shard;

	private final boolean counted;

	private Operation(DatabaseState state, Request request, int shard, boolean counted) {
		this.state = state;
		this.request = request;
		this.shard = shard;
		this.counted = counted;
	}

	/**
	 * A request's operation, which the database's figures count.
	 * @param state the database
	 * @param request the request
	 * @param shard the shard it runs on
	 * @return the operation
	 */
	static Operation counted(DatabaseState state, Request request, int shard) {
		return new Operation(state, request, shard, true);
	}

	/**
	 * A scattered read's operation on another shard than the first, which the database's
	 * figures leave out.
	 * @param state the database
	 * @param request the request
	 * @param shard the shard it runs on
	 * @return the operation
	 */
	static Operation uncounted(DatabaseState state, Request request, int shard) {
		return new Operation(state, request, shard, false);
	}

	/**
	 * Runs the operation.
	 * @param ran what to do once it ran
	 * @param refused what to do with why the shard refused it
	 */
	void run(Runnable ran, Consumer<FailureReason> refused) {
		double now = now();
		if (settings().partitions() > 1 && this.counted) {
			metrics().count(DatabaseCounters.SHARD + this.shard, now);
		}
		if (!controls().admits(this.shard, operation(), now)) {
			refused.accept(FailureReason.THROTTLED);
			return;
		}
		DatabaseInstance instance = shards().pick(this.shard, operation());
		instance.pool().acquire(this.request, this.state.nodeId(), (acquired) -> {
			switch (acquired) {
				case WorkerPool.Taken taken -> new Connected(instance, taken.slot(), ran).takeTicket();
				case WorkerPool.Refused refusal -> refused.accept(refusal.reason());
			}
		});
	}

	private NodeMetrics metrics() {
		return this.state.metrics();
	}

	private DatabaseSettings settings() {
		return this.state.settings();
	}

	private ShardControls controls() {
		return this.state.controls();
	}

	private OperationDraws draws() {
		return this.state.draws();
	}

	private Simulation simulation() {
		return this.state.simulation();
	}

	private DatabaseShards shards() {
		return this.state.shards();
	}

	private @Nullable ReplicationPipeline replication() {
		return this.state.replication();
	}

	private RequestType operation() {
		return this.request.operation();
	}

	private int key() {
		return this.request.arrival().key();
	}

	private double now() {
		return simulation().now();
	}

	private void span(SpanKind kind, double start, String detail) {
		this.request.span(this.state.nodeId(), kind, new Request.Interval(start, now()), detail);
	}

	/**
	 * The operation once it holds a connection.
	 */
	private final class Connected {

		private final DatabaseInstance instance;

		private final Resource.Request slot;

		private final double start;

		private final Runnable ran;

		private @Nullable Ticket ticket;

		private LockTable.@Nullable Held lock;

		private double serviceMs;

		private @Nullable Shipped shipped;

		Connected(DatabaseInstance instance, Resource.Request slot, Runnable ran) {
			this.instance = instance;
			this.slot = slot;
			this.start = now();
			this.ran = ran;
		}

		// a ticket, when tickets limit the operation
		void takeTicket() {
			Resource pool = this.instance.tickets(operation());
			if (pool == null) {
				lockRow();
				return;
			}
			double asked = now();
			Resource.Request taken = pool.request();
			this.ticket = new Ticket(pool, taken);
			taken.then(() -> {
				if (now() > asked) {
					metrics().recordMean(DatabaseCounters.TICKET_WAIT_MS, now(), now() - asked);
					span(SpanKind.TICKETS, asked, "waiting for a WiredTiger ticket");
				}
				lockRow();
			});
		}

		// a write's own lock on its row, or a reader waiting for a writer's
		private void lockRow() {
			Optional<LockTable> table = controls().locks(Operation.this.shard);
			DatabaseSettings settings = settings();
			if (operation() == RequestType.WRITE && settings.locksRows() && table.isPresent()) {
				lockForWrite(table.get());
			}
			else if (operation() == RequestType.READ && settings.readersWaitForWriters() && table.isPresent()) {
				waitForWriter(table.get());
			}
			else {
				execute();
			}
		}

		private void lockForWrite(LockTable table) {
			double asked = now();
			LockTable.Held held = table.acquire(key());
			held.request().then(() -> {
				double waitMs = now() - asked;
				metrics().recordMean(DatabaseCounters.LOCK_WAIT_MS, now(), waitMs);
				if (waitMs > 0) {
					metrics().count(DatabaseCounters.LOCK_WAITS, now());
					span(SpanKind.LOCK, asked, "row lock on key " + key());
				}
				this.lock = held;
				execute();
			});
		}

		private void waitForWriter(LockTable table) {
			Optional<Resource> held = table.held(key());
			if (held.isEmpty()) {
				execute();
				return;
			}
			Resource writersLock = held.get();
			double asked = now();
			Resource.Request turn = writersLock.request();
			turn.then(() -> {
				writersLock.release(turn);
				metrics().count(DatabaseCounters.READS_BLOCKED, now());
				span(SpanKind.LOCK, asked, "waiting for a writer's lock");
				table.forgetIfFree(key(), writersLock);
				execute();
			});
		}

		// the operation's time on the instance, then disk reads for a read that missed
		// the buffer cache
		private void execute() {
			double started = now();
			this.serviceMs = draws().serviceMs(operation());
			shards().serve(this.instance, this.serviceMs, () -> {
				OperationDraws draws = draws();
				if (!draws.missesBuffer(operation())) {
					executed(started);
					return;
				}
				int reads = draws.diskReads();
				metrics().count(DatabaseCounters.DISK_READS, now(), reads);
				readDisk(reads, () -> executed(started));
			});
		}

		private void readDisk(int reads, Runnable next) {
			if (reads == 0) {
				next.run();
				return;
			}
			double readMs = draws().diskReadMs();
			simulation().after(readMs, () -> readDisk(reads - 1, next));
		}

		private void executed(double started) {
			boolean write = operation() == RequestType.WRITE;
			span(SpanKind.EXECUTE, started, write ? "write" : "read");
			if (write) {
				commit();
				return;
			}
			ReplicationPipeline replication = replication();
			if (replication != null && replication.stale(Operation.this.shard, key(), this.instance)) {
				metrics().count(DatabaseCounters.STALE_READS, now());
				span(SpanKind.STALE, now(), "replica had not replayed the latest write");
			}
			finish();
		}

		// a write waits for the log's flush, then lets its row's lock go
		private void commit() {
			Optional<LogFlusher> log = controls().flusher(Operation.this.shard);
			if (log.isEmpty()) {
				committed();
				return;
			}
			double asked = now();
			log.get().commit().then(() -> {
				metrics().recordMean(DatabaseCounters.COMMIT_WAIT_MS, now(), now() - asked);
				span(SpanKind.COMMIT, asked, "write-ahead log flush");
				committed();
			});
		}

		// lets the row's lock go, then ships the write to the shard's replicas
		private void committed() {
			LockTable.Held held = this.lock;
			if (held != null) {
				controls().locks(Operation.this.shard).orElseThrow().release(held);
			}
			ReplicationPipeline replication = replication();
			if (replication != null) {
				this.shipped = new Shipped(replication,
						replication.replicate(Operation.this.shard, key(), this.serviceMs));
			}
			finish();
		}

		// lets the ticket go, waits for the replicas a write waits for, then lets the
		// connection go
		private void finish() {
			Ticket held = this.ticket;
			if (held != null) {
				held.pool().release(held.request());
			}
			Shipped write = this.shipped;
			Signal acknowledged = (write != null) ? write.job().acknowledged() : null;
			if (write == null || acknowledged == null) {
				release();
				return;
			}
			awaitReplicas(write, acknowledged);
		}

		private void awaitReplicas(Shipped write, Signal acknowledged) {
			double asked = now();
			write.replication().afterAcknowledged(acknowledged, () -> {
				metrics().recordMean(DatabaseCounters.REPLICA_ACK_MS, now(), now() - asked);
				span(SpanKind.REPLICATION, asked, "waiting for " + write.job().acksNeeded() + " replica(s)");
				release();
			});
		}

		private void release() {
			this.instance.pool().release(this.slot);
			if (Operation.this.counted) {
				double now = now();
				metrics().service().record(now, this.serviceMs);
				metrics().hold().record(now, now - this.start);
				if (this.instance.isReplica()) {
					metrics().count(DatabaseCounters.REPLICA_READS, now);
				}
			}
			this.ran.run();
		}

	}

	/**
	 * A ticket an operation holds.
	 *
	 * @param pool the instance's tickets
	 * @param request the operation's request for one
	 */
	private record Ticket(Resource pool, Resource.Request request) {
	}

	/**
	 * A write shipped to its shard's replicas.
	 *
	 * @param replication the database's replication
	 * @param job the write to replay
	 */
	private record Shipped(ReplicationPipeline replication, ApplyJob job) {
	}

}
