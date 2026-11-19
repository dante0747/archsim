package io.archsim.benchmarks.load;

import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;

import io.archsim.benchmarks.docker.CgroupReading;
import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.LoadStart;
import io.archsim.benchmarks.docker.Ticker;
import io.archsim.engine.numeric.PythonRound;

/**
 * k6's own CPU over the middle of a measured window: its container ({@code compose run}
 * names it {@code archsim-bench-k6-run-...}) read 2 s into the window and again 2 s
 * before its end, on a thread of its own beside the run. The window is counted from the
 * load's start ({@link LoadStart}), not from the container's: k6 starts its virtual users
 * first. A load generator short of CPU sends late and measures its own queue, so each
 * measured window keeps what k6 used.
 */
public final class K6Cpu implements AutoCloseable {

	/** A thread of the watch's own, beside k6's run. */
	public static final Executor OWN_THREAD = (task) -> Thread.ofPlatform().daemon().name("k6-cpu").start(task);

	private static final String CONTAINER = "name=archsim-bench-k6-run";

	private static final double FIND_S = 10;

	private static final double FIND_POLL_S = 0.2;

	/** How long into the window, and before its end, the readings are taken. */
	private static final double MARGIN_S = 2.0;

	private static final double SHORTEST_S = 1.0;

	private static final long JOIN_S = 5;

	private static final double MICROSECONDS = 1e6;

	private static final int DECIMALS = 4;

	private final Reading reading;

	private final CompletableFuture<Void> watching;

	private K6Cpu(Reading reading, CompletableFuture<Void> watching) {
		this.reading = reading;
		this.watching = watching;
	}

	/**
	 * Starts watching k6's container.
	 * @param stack what runs Docker's commands, the service that says when the load
	 * started, and the clock
	 * @param window the run's warm-up, which is not read, and its measured seconds
	 * @param executor what runs the watch: a thread of its own, or the caller's, which
	 * then watches before this returns
	 * @return the watch
	 */
	public static K6Cpu watch(Instruments stack, K6.Window window, Executor executor) {
		Reading reading = new Reading();
		return new K6Cpu(reading, CompletableFuture.runAsync(() -> reading.observe(stack, window), executor));
	}

	/**
	 * k6's busy cores, once the watch ended.
	 * @return the cores; empty when its container was not found or not read, or the load
	 * never started
	 */
	public Optional<Double> cores() {
		return Optional.ofNullable(this.reading.cores);
	}

	/**
	 * Ends the watch at its next wait: for k6's container, for the load to start, or
	 * between its readings.
	 */
	void stop() {
		this.reading.stopped.countDown();
	}

	/**
	 * Ends the watch at its next wait, and waits five seconds at most for it to end: k6's
	 * run is over, so nothing is left to read.
	 */
	@Override
	public void close() {
		stop();
		try {
			this.watching.get(JOIN_S, TimeUnit.SECONDS);
		}
		catch (ExecutionException | TimeoutException ex) {
			// no cores: the watch failed or is still reading
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * What the watch read: k6's busy cores, once read.
	 */
	private static final class Reading {

		private final CountDownLatch stopped = new CountDownLatch(1);

		private volatile @Nullable Double cores;

		void observe(Instruments stack, K6.Window window) {
			Ticker ticker = stack.ticker();
			Optional<String> container = find(stack.compose(), ticker);
			if (container.isEmpty()) {
				return;
			}
			OptionalDouble started = LoadStart.await(stack, this.stopped);
			if (started.isEmpty()) {
				return;
			}
			double first = started.getAsDouble() + window.warmup() + MARGIN_S;
			if (ticker.awaitStop(this.stopped, Math.max(0, first - ticker.now()))) {
				return;
			}
			double start = ticker.now();
			Optional<Long> before = usage(stack.compose(), container.get());
			if (ticker.awaitStop(this.stopped, Math.max(SHORTEST_S, window.duration() - 2 * MARGIN_S))) {
				return;
			}
			double end = ticker.now();
			Optional<Long> after = usage(stack.compose(), container.get());
			if (before.isPresent() && after.isPresent() && end > start) {
				this.cores = PythonRound.round((after.get() - before.get()) / MICROSECONDS / (end - start), DECIMALS);
			}
		}

		// k6's container, once compose has started it; none when the watch is stopped
		// first
		private Optional<String> find(Compose compose, Ticker ticker) {
			double deadline = ticker.now() + FIND_S;
			while (ticker.now() < deadline) {
				Optional<String> found = Arrays
					.stream(compose.docker("ps", "-q", "--filter", CONTAINER).output().split("\\s+"))
					.filter((id) -> !id.isBlank())
					.findFirst();
				if (found.isPresent() || ticker.awaitStop(this.stopped, FIND_POLL_S)) {
					return found;
				}
			}
			return Optional.empty();
		}

		private static Optional<Long> usage(Compose compose, String container) {
			String stat = compose.docker("exec", container, "cat", "/sys/fs/cgroup/cpu.stat").output();
			return Optional.ofNullable(CgroupReading.of(0, stat).usageUsec());
		}

	}

}
