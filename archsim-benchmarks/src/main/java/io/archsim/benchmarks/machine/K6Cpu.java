package io.archsim.benchmarks.machine;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;

import io.archsim.benchmarks.docker.CgroupReading;
import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.Ticker;
import io.archsim.engine.numeric.PythonRound;

/**
 * k6's own CPU over the middle of a measured run: its container ({@code compose run}
 * names it {@code archsim-bench-k6-run-...}) read 2 s in and again 2 s before the end, on
 * a thread of its own beside the run.
 */
final class K6Cpu implements AutoCloseable {

	/** A thread of the watch's own, beside k6's run. */
	static final Executor OWN_THREAD = (task) -> Thread.ofPlatform().daemon().name("k6-cpu").start(task);

	private static final String CONTAINER = "name=archsim-bench-k6-run";

	private static final double FIND_S = 10;

	private static final double FIND_POLL_S = 0.2;

	/** How long into the run, and before its end, the readings are taken. */
	private static final double MARGIN_S = 2.0;

	/** k6's warm-up is in the run too: the watch ends this long before the run's end. */
	private static final double LEAD_S = 6.0;

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
	 * @param compose what runs Docker's commands
	 * @param ticker the clock
	 * @param seconds the run's measured seconds
	 * @param executor what runs the watch: a thread of its own, or the caller's, which
	 * then watches before this returns
	 * @return the watch
	 */
	static K6Cpu watch(Compose compose, Ticker ticker, double seconds, Executor executor) {
		Reading reading = new Reading();
		return new K6Cpu(reading,
				CompletableFuture.runAsync(() -> reading.observe(compose, ticker, seconds), executor));
	}

	/**
	 * k6's busy cores, once the watch ended.
	 * @return the cores; empty when its container was not found or not read
	 */
	Optional<Double> cores() {
		return Optional.ofNullable(this.reading.cores);
	}

	/**
	 * Waits, five seconds at most, for the watch to end.
	 */
	@Override
	public void close() {
		try {
			this.watching.get(JOIN_S, TimeUnit.SECONDS);
		}
		catch (ExecutionException | TimeoutException ex) {
			// no cores: the watch failed or is still waiting
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * What the watch read: k6's busy cores, once read.
	 */
	private static final class Reading {

		private volatile @Nullable Double cores;

		void observe(Compose compose, Ticker ticker, double seconds) {
			Optional<String> container = find(compose, ticker);
			if (container.isEmpty()) {
				return;
			}
			ticker.sleep(MARGIN_S);
			double start = ticker.now();
			Optional<Long> before = usage(compose, container.get());
			ticker.sleep(Math.max(SHORTEST_S, seconds - LEAD_S));
			double end = ticker.now();
			Optional<Long> after = usage(compose, container.get());
			if (before.isPresent() && after.isPresent() && end > start) {
				this.cores = PythonRound.round((after.get() - before.get()) / MICROSECONDS / (end - start), DECIMALS);
			}
		}

		// k6's container, once compose has started it
		private static Optional<String> find(Compose compose, Ticker ticker) {
			double deadline = ticker.now() + FIND_S;
			while (ticker.now() < deadline) {
				Optional<String> found = Arrays
					.stream(compose.docker("ps", "-q", "--filter", CONTAINER).output().split("\\s+"))
					.filter((id) -> !id.isBlank())
					.findFirst();
				if (found.isPresent()) {
					return found;
				}
				ticker.sleep(FIND_POLL_S);
			}
			return Optional.empty();
		}

		private static Optional<Long> usage(Compose compose, String container) {
			String stat = compose.docker("exec", container, "cat", "/sys/fs/cgroup/cpu.stat").output();
			return Optional.ofNullable(CgroupReading.of(0, stat).usageUsec());
		}

	}

}
