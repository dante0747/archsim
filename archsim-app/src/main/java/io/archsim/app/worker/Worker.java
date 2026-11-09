package io.archsim.app.worker;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import io.archsim.app.config.ArchsimProperties;

/**
 * A worker's life, from the {@code worker} role's start to its stop: it registers in
 * {@code job_workers}, beats there on a platform thread of its own, and logs that it is
 * ready in the line the black-box suite and the end-to-end launcher wait for; when the
 * role stops, it stops beating and leaves. A database it cannot reach fails its start;
 * once it runs, it costs a beat, or the row at stop, never the worker's life.
 */
@Component
@Profile("worker")
final class Worker implements SmartLifecycle {

	private static final Logger LOGGER = LoggerFactory.getLogger(Worker.class);

	/** How long a stop waits for a beat already under way, as the Python worker did. */
	private static final Duration LAST_BEAT = Duration.ofSeconds(10);

	private final WorkerRepository repository;

	private final Clock clock;

	private final WorkerIdentity identity = WorkerIdentity.ofThisProcess();

	private final int slots;

	private final Duration beatEvery;

	// the beats while the worker runs; none before it starts or once it stops
	private final AtomicReference<@Nullable PeriodicTask> beats = new AtomicReference<>();

	Worker(WorkerRepository repository, Clock clock, ArchsimProperties archsim, WorkerProperties settings) {
		this.repository = repository;
		this.clock = clock;
		this.slots = archsim.concurrentRuns();
		this.beatEvery = settings.heartbeatPeriod();
	}

	@Override
	public void start() {
		beat();
		this.beats.set(PeriodicTask.start("archsim-worker-heartbeat", this.beatEvery, this::beatOrWarn));
		LOGGER.info("worker {} takes up to {} run(s) at once", this.identity.id(), this.slots);
	}

	@Override
	public void stop() {
		// a beat under way ends before the worker leaves, so that it cannot register it
		// again
		PeriodicTask beating = this.beats.getAndSet(null);
		if (beating != null) {
			beating.stop(LAST_BEAT);
		}
		leave();
		LOGGER.info("worker {} stopped", this.identity.id());
	}

	@Override
	public boolean isRunning() {
		return this.beats.get() != null;
	}

	private void beat() {
		this.repository.beat(new Heartbeat(this.identity, this.slots, 0), this.clock.instant());
	}

	private void beatOrWarn() {
		try {
			beat();
		}
		catch (DataAccessException ex) {
			LOGGER.warn("worker {} missed a beat: {}", this.identity.id(), ex.getMessage());
		}
	}

	private void leave() {
		try {
			this.repository.forget(this.identity);
		}
		catch (DataAccessException ex) {
			LOGGER.warn("worker {} could not leave job_workers: {}", this.identity.id(), ex.getMessage());
		}
	}

}
