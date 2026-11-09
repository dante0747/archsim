package io.archsim.app.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import io.archsim.engine.simulation.SimulationLimits;

/**
 * Runs claimed by worker {@code a} in a test's queue, simulated by stand-ins on threads
 * of the fixture's, with a cancel grace of 100 ms. Unless a test says otherwise, each
 * look at the time finds a second gone, so every progress is written.
 */
final class ExecutionFixture implements AutoCloseable {

	/** How long a cancelled run may take to stop, unless a test says otherwise. */
	static final Duration GRACE = Duration.ofMillis(100);

	/** When worker {@code a} claims its runs. */
	static final Instant NOW = QueueFixture.QUEUED.plusSeconds(60);

	private final QueueFixture queue;

	private final ExecutorService runs = Executors.newCachedThreadPool(Thread.ofPlatform().daemon(true).factory());

	private final ScheduledExecutorService timers = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).factory());

	private final Duration grace;

	private final AtomicLong nanos = new AtomicLong();

	ExecutionFixture(QueueFixture queue) {
		this(queue, GRACE);
	}

	ExecutionFixture(QueueFixture queue, Duration grace) {
		this.queue = queue;
		this.grace = grace;
	}

	/**
	 * Queues a run and has worker {@code a} claim it.
	 * @return the run
	 */
	ClaimedRun claimed() {
		this.queue.queue("ws", 1);
		return QueueParts.claims(this.queue, "rate-limits=false")
			.claim(new ClaimRequest("a", 1, Set.of()), NOW)
			.getFirst();
	}

	/**
	 * A claimed run's execution, with a hard deadline 30 s past its limit.
	 * @param run the run
	 * @param simulator its simulator
	 * @return the execution, not started
	 */
	Execution execution(ClaimedRun run, RunSimulator simulator) {
		return execution(run, simulator, Duration.ofSeconds(30), () -> this.nanos.addAndGet(1_000_000_000L));
	}

	/**
	 * A claimed run's execution.
	 * @param run the run
	 * @param simulator its simulator
	 * @param deadlineMargin added to its limit for its hard deadline
	 * @param time the time, for the pace of its progress writes
	 * @return the execution, not started
	 */
	Execution execution(ClaimedRun run, RunSimulator simulator, Duration deadlineMargin, LongSupplier time) {
		WorkerContext context = new WorkerContext(new WorkerIdentity("a", "host-a", 1), 3, SimulationLimits.DEFAULT,
				new WorkerProperties(30.0, 2.0, 10.0, this.grace.toMillis() / 1000.0),
				Clock.fixed(NOW, ZoneOffset.UTC));
		RunWriter writer = new RunWriter(QueueParts.leases(this.queue), context, (duration) -> {
		});
		return new Execution(run, new RunParts(simulator, writer, new RunOutcomes(), context, deadlineMargin, time,
				this.runs, this.timers));
	}

	@Override
	public void close() {
		this.runs.shutdownNow();
		this.timers.shutdownNow();
	}

}
