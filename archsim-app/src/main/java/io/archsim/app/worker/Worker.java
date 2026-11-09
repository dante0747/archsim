package io.archsim.app.worker;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A worker's life, from the {@code worker} role's start to its stop (ADR-0053). It starts
 * by beating in {@code job_workers} and settling the runs workers that died left, then
 * claims runs and beats on threads of its own, and logs that it is ready in the line the
 * black-box suite and the end-to-end launcher wait for. It stops by claiming no more,
 * giving its runs back (the heartbeat keeps their leases meanwhile), then leaving.
 */
@Component
@Profile("worker")
final class Worker implements SmartLifecycle {

	private static final Logger LOGGER = LoggerFactory.getLogger(Worker.class);

	/** How long a stop waits for its runs to be given back, as the Python worker did. */
	private static final Duration GIVING_BACK = Duration.ofSeconds(15);

	/** How long a stop waits for a beat already under way. */
	private static final Duration LAST_BEAT = Duration.ofSeconds(10);

	private final Heart heart;

	private final ClaimLoop claims;

	private final Executions executions;

	private final WorkerContext context;

	// the beats while the worker runs; none before it starts or once it stops
	private final AtomicReference<@Nullable PeriodicTask> beats = new AtomicReference<>();

	Worker(Heart heart, ClaimLoop claims, Executions executions, WorkerContext context) {
		this.heart = heart;
		this.claims = claims;
		this.executions = executions;
		this.context = context;
	}

	@Override
	public void start() {
		this.heart.resume();
		this.heart.beat();
		this.heart.reap();
		this.claims.start();
		this.beats.set(PeriodicTask.start("archsim-worker-heartbeat", this.context.timings().heartbeatPeriod(),
				this.heart::beatOnce));
		LOGGER.info("worker {} takes up to {} run(s) at once", this.context.workerId(), this.context.slots());
	}

	@Override
	public void stop() {
		this.claims.stop();
		this.heart.drain();
		int running = this.executions.ids().size();
		if (running > 0) {
			LOGGER.info("worker {} stopping: giving back {} run(s)", this.context.workerId(), running);
		}
		this.executions.giveBackAll();
		this.executions.awaitAll(GIVING_BACK);
		// a beat under way ends before the worker leaves, so that it cannot register it
		// again
		PeriodicTask beating = this.beats.getAndSet(null);
		if (beating != null) {
			beating.stop(LAST_BEAT);
		}
		this.heart.leave();
		LOGGER.info("worker {} stopped", this.context.workerId());
	}

	@Override
	public boolean isRunning() {
		return this.beats.get() != null;
	}

}
