package io.archsim.app.worker;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * A worker's heartbeat, as the Python worker's: at each beat it renews the leases of the
 * runs it simulates, stopping those another worker took and passing on the cancels asked
 * for, then says in {@code job_workers} that it is alive; every reap period, unless it is
 * stopping, it settles the runs whose leases ran out, any worker's. The database missing
 * a beat costs only that beat.
 */
@Component
@Profile("worker")
final class Heart {

	private static final Logger LOGGER = LoggerFactory.getLogger(Heart.class);

	private final LeaseRepository leases;

	private final WorkerRepository registry;

	private final Executions executions;

	private final WorkerContext context;

	private final AtomicBoolean draining = new AtomicBoolean();

	private final AtomicReference<Instant> nextReap = new AtomicReference<>(Instant.MIN);

	Heart(LeaseRepository leases, WorkerRepository registry, Executions executions, WorkerContext context) {
		this.leases = leases;
		this.registry = registry;
		this.executions = executions;
		this.context = context;
	}

	/** One beat: renew, say so, and reap when it is time. */
	void beatOnce() {
		renew();
		beat();
		Instant now = this.context.clock().instant();
		if (!this.draining.get() && !now.isBefore(this.nextReap.get())) {
			reap();
		}
	}

	/** Renews the leases of the runs the worker simulates; it learns which it lost. */
	void renew() {
		// taken first: a run claimed after it is not lost
		Map<String, Execution> now = this.executions.now();
		if (now.isEmpty()) {
			return;
		}
		Renewal renewal;
		try {
			renewal = this.leases.renew(this.context.workerId(), now.keySet(),
					this.context.clock().instant().plus(this.context.timings().lease()));
		}
		catch (DataAccessException | TransactionException ex) {
			LOGGER.warn("could not renew leases", ex);
			return;
		}
		now.forEach((id, execution) -> {
			if (!renewal.owned().contains(id)) {
				execution.abandon(Execution.Abandoned.LOST);
			}
			else if (renewal.cancelRequested().contains(id)) {
				execution.requestCancel();
			}
		});
	}

	/** Says the worker is alive, with its runs, in {@code job_workers}. */
	void beat() {
		try {
			this.registry.beat(new Heartbeat(this.context.identity(), this.context.slots(),
					this.executions.ids().size(), this.draining.get()), this.context.clock().instant());
		}
		catch (DataAccessException ex) {
			LOGGER.warn("worker {} missed a beat: {}", this.context.workerId(), ex.getMessage());
		}
	}

	/** Settles the runs whose leases ran out, and forgets workers long gone. */
	void reap() {
		Instant now = this.context.clock().instant();
		this.nextReap.set(now.plus(this.context.timings().reapPeriod()));
		try {
			Reaped reaped = this.leases.reap(now);
			this.registry.forgetStale(now);
			if (reaped.total() > 0) {
				LOGGER.warn("runs whose worker stopped: {} requeued, {} failed, {} cancelled", reaped.requeued(),
						reaped.failed(), reaped.cancelled());
			}
			if (reaped.requeued() > 0) {
				this.executions.wake();
			}
		}
		catch (DataAccessException | TransactionException ex) {
			LOGGER.warn("could not reap expired runs", ex);
		}
	}

	/** From now on the worker is stopping: it beats as draining and reaps no more. */
	void drain() {
		this.draining.set(true);
	}

	/** The worker starts, or starts again: it beats as running and reaps. */
	void resume() {
		this.draining.set(false);
	}

	/** Removes the worker's row, as it stops. */
	void leave() {
		try {
			this.registry.forget(this.context.identity());
		}
		catch (DataAccessException ex) {
			LOGGER.warn("worker {} could not leave job_workers: {}", this.context.workerId(), ex.getMessage());
		}
	}

}
