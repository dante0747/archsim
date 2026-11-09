package io.archsim.app.worker;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import io.archsim.engine.simulation.SimulationLimits;

/**
 * Worker {@code w1} over a test's queue, built as the worker role builds it, but for its
 * simulator and its pace: a lease of 30 s, a beat every 50 ms and a reap every 100 ms.
 */
final class WorkerFixture implements AutoCloseable {

	private final ExecutorService runs = Executors.newCachedThreadPool(Thread.ofPlatform().daemon(true).factory());

	private final ScheduledExecutorService timers = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).factory());

	private final Executions executions;

	private final Worker worker;

	/**
	 * A worker over a queue.
	 * @param queue the test's queue
	 * @param simulator what simulates its runs
	 * @param slots the runs it takes at once
	 */
	WorkerFixture(QueueFixture queue, RunSimulator simulator, int slots) {
		WorkerContext context = new WorkerContext(new WorkerIdentity("w1", "host-w", 1), slots,
				SimulationLimits.DEFAULT, new WorkerProperties(30.0, 0.05, 0.1, 10.0), Clock.systemUTC());
		LeaseRepository leases = QueueParts.leases(queue);
		RunParts parts = new RunParts(simulator, new RunWriter(leases, context, (duration) -> {
		}), new RunOutcomes(), context, Duration.ofSeconds(30), System::nanoTime, this.runs, this.timers);
		this.executions = new Executions(parts);
		Heart heart = new Heart(leases, new WorkerRepository(queue.jdbc()), this.executions, context);
		ClaimLoop claims = new ClaimLoop(QueueParts.claims(queue, "rate-limits=false"), this.executions, context);
		this.worker = new Worker(heart, claims, this.executions, context);
	}

	Worker worker() {
		return this.worker;
	}

	Executions executions() {
		return this.executions;
	}

	@Override
	public void close() {
		if (this.worker.isRunning()) {
			this.worker.stop();
		}
		this.runs.shutdownNow();
		this.timers.shutdownNow();
	}

}
