package io.archsim.app.worker;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.support.TaskUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * A worker's claims, on a platform thread of their own, as the Python worker's claim
 * thread: whenever it has a free slot it claims queued runs and starts them, then waits
 * for a slot to free, runs to be requeued, or a second to pass. A claim the database
 * fails is logged, and the next comes as planned.
 */
@Component
@Profile("worker")
final class ClaimLoop {

	private static final Logger LOGGER = LoggerFactory.getLogger(ClaimLoop.class);

	/** The longest wait between two looks at the queue. */
	private static final Duration POLL = Duration.ofSeconds(1);

	private final RunClaims claims;

	private final Executions executions;

	private final WorkerContext context;

	private final AtomicBoolean stopping = new AtomicBoolean();

	private final AtomicReference<@Nullable Thread> thread = new AtomicReference<>();

	// one look at the queue: whatever goes wrong in it is logged, and the next look comes
	private final Runnable claimPass = TaskUtils.decorateTaskWithErrorHandler(this::claimOnce,
			TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER, true);

	ClaimLoop(RunClaims claims, Executions executions, WorkerContext context) {
		this.claims = claims;
		this.executions = executions;
		this.context = context;
	}

	/** Starts claiming. */
	void start() {
		this.stopping.set(false);
		this.thread.set(Thread.ofPlatform().name("archsim-claims").daemon(true).start(this::loop));
	}

	/** Stops claiming; returns once no claim is under way, so nothing new starts. */
	void stop() {
		this.stopping.set(true);
		this.executions.wake();
		Thread claiming = this.thread.getAndSet(null);
		if (claiming != null) {
			try {
				claiming.join();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void loop() {
		try {
			while (!this.stopping.get()) {
				this.claimPass.run();
				this.executions.awaitWake(POLL);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private void claimOnce() {
		int free = this.executions.free();
		if (free == 0) {
			return;
		}
		try {
			this.claims
				.claim(new ClaimRequest(this.context.workerId(), free, this.executions.ids()),
						this.context.clock().instant())
				.forEach(this.executions::start);
		}
		catch (DataAccessException | TransactionException ex) {
			LOGGER.warn("could not claim runs", ex);
		}
	}

}
