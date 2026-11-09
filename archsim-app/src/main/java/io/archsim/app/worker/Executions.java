package io.archsim.app.worker;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The runs a worker simulates: each started as it is claimed, and held until its outcome
 * is written or it is given back, and its thread is free; only then is its slot free.
 * Whoever waits for a slot is woken when one frees, or when runs are requeued.
 */
@Component
@Profile("worker")
final class Executions {

	private static final Logger LOGGER = LoggerFactory.getLogger(Executions.class);

	private final RunParts parts;

	private final Map<String, Running> running = new ConcurrentHashMap<>();

	private final Semaphore wake = new Semaphore(0);

	Executions(RunParts parts) {
		this.parts = parts;
	}

	/**
	 * Starts simulating a claimed run, listed before it starts so that its end finds it.
	 * @param run the run
	 */
	void start(ClaimedRun run) {
		Execution execution = new Execution(run, this.parts);
		CompletableFuture<Void> listed = new CompletableFuture<>();
		CompletableFuture<Void> done = listed.thenCompose((ignored) -> execution.start())
			.whenComplete((ignored, failure) -> ended(run.id()));
		this.running.put(run.id(), new Running(execution, done));
		listed.complete(null);
	}

	/**
	 * The slots free for more runs.
	 * @return the runs the worker may still take
	 */
	int free() {
		return Math.max(0, this.parts.context().slots() - this.running.size());
	}

	/**
	 * The runs being simulated now.
	 * @return each by its id
	 */
	Map<String, Execution> now() {
		return this.running.entrySet()
			.stream()
			.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, (entry) -> entry.getValue().execution()));
	}

	/**
	 * The ids of the runs being simulated now, which a claim must not take again.
	 * @return the ids
	 */
	Set<String> ids() {
		return Set.copyOf(this.running.keySet());
	}

	/**
	 * Asks every run to stop, as the worker stops, so that it is given back.
	 */
	void giveBackAll() {
		this.running.values().forEach((run) -> run.execution().abandon(Execution.Abandoned.REQUEUE));
	}

	/**
	 * Waits for the runs to end, a while at most.
	 * @param timeout the longest wait
	 */
	void awaitAll(Duration timeout) {
		CompletableFuture<?>[] ends = this.running.values()
			.stream()
			.map(Running::done)
			.toArray(CompletableFuture[]::new);
		try {
			CompletableFuture.allOf(ends).get(timeout.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (TimeoutException ex) {
			LOGGER.warn("{} run(s) did not end in time", this.running.size());
		}
		catch (ExecutionException ex) {
			LOGGER.warn("a run ended with a fault: {}", ex.getMessage());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/** Wakes whoever waits for a slot: one freed, or runs were requeued. */
	void wake() {
		this.wake.release();
	}

	/**
	 * Waits until woken, a while at most.
	 * @param timeout the longest wait
	 * @throws InterruptedException when the waiting thread is interrupted
	 */
	void awaitWake(Duration timeout) throws InterruptedException {
		if (this.wake.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
			this.wake.drainPermits();
		}
	}

	private void ended(String runId) {
		this.running.remove(runId);
		wake();
	}

	/**
	 * A run being simulated, and when it ends.
	 *
	 * @param execution the run
	 * @param done done once it has ended
	 */
	private record Running(Execution execution, CompletableFuture<Void> done) {

	}

}
