package io.archsim.benchmarks.docker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.engine.numeric.PythonRound;

/**
 * Reads each container's CPU and memory through the window k6 measures, beside a step, on
 * a thread of its own.
 *
 * <p>
 * The load starts when the service first counts a finished request (its counters start at
 * {@code /reset}, which runs just before k6); the window starts the warm-up later and
 * lasts the measured duration, less a second for the late detection of the start. k6's
 * own container start and stop (several seconds) are outside it, unlike
 * {@code containerCpuShare}, which spans the whole k6 command. A calibration pass, which
 * k6 records from its start, is sampled from the start. A reading that fails ends the
 * sampling, as run.py's thread ended, and the result keeps what was read before it.
 */
public final class ResourceSampler implements AutoCloseable {

	/** A thread of the sampler's own, beside the step it reads. */
	public static final Executor OWN_THREAD = (
			task) -> Thread.ofPlatform().daemon().name("resource-sampler").start(task);

	private static final long JOIN_TIMEOUT_S = 60;

	private final Sampling sampling;

	private final CompletableFuture<Void> running;

	private ResourceSampler(Sampling sampling, CompletableFuture<Void> running) {
		this.sampling = sampling;
		this.running = running;
	}

	/**
	 * Starts reading a stack's containers on a thread of its own.
	 * @param instruments what reads them
	 * @param services the containers' compose services
	 * @param window the window to read them through
	 * @return the sampler, reading
	 */
	public static ResourceSampler start(Instruments instruments, List<String> services, Window window) {
		return start(instruments, services, window, OWN_THREAD);
	}

	/**
	 * Starts reading a stack's containers.
	 * @param instruments what reads them
	 * @param services the containers' compose services
	 * @param window the window to read them through
	 * @param executor what runs the reading: a thread of its own, or the caller's, which
	 * then reads the whole window before this returns
	 * @return the sampler
	 */
	public static ResourceSampler start(Instruments instruments, List<String> services, Window window,
			Executor executor) {
		Sampling sampling = new Sampling(instruments, services, window);
		return new ResourceSampler(sampling, CompletableFuture.runAsync(sampling::run, executor));
	}

	/**
	 * Each container's CPU and memory over the window, once closed.
	 * @return {@code method}, {@code seconds}, {@code readings} and {@code containers};
	 * empty when no container was read
	 */
	public Optional<ObjectNode> result() {
		ObjectNode containers = JsonNodeFactory.instance.objectNode();
		List<Double> times = new ArrayList<>();
		int most = 0;
		for (Map.Entry<String, List<CgroupReading>> container : this.sampling.readings().entrySet()) {
			List<CgroupReading> read = List.copyOf(container.getValue());
			WindowResources.of(read).ifPresent((resources) -> containers.set(container.getKey(), resources));
			read.forEach((reading) -> times.add(reading.seconds()));
			most = Math.max(most, read.size());
		}
		if (containers.isEmpty()) {
			return Optional.empty();
		}
		ObjectNode result = JsonNodeFactory.instance.objectNode().put("method", "window");
		result.put("seconds", PythonRound.round(Collections.max(times) - Collections.min(times), 2));
		result.put("readings", most);
		result.set("containers", containers);
		return Optional.of(result);
	}

	/**
	 * Why the reading ended early, once closed.
	 * @return the failure's message (its class when it has none); empty when the reading
	 * ran its course
	 */
	public Optional<String> failure() {
		if (this.running.state() != Future.State.FAILED) {
			return Optional.empty();
		}
		Throwable failure = this.running.exceptionNow();
		Throwable cause = (failure instanceof CompletionException && failure.getCause() != null) ? failure.getCause()
				: failure;
		return Optional.of(Objects.requireNonNullElse(cause.getMessage(), cause.toString()));
	}

	/**
	 * Stops reading and waits, a minute at most, for the last reading.
	 */
	@Override
	public void close() {
		this.sampling.stop();
		try {
			this.running.get(JOIN_TIMEOUT_S, TimeUnit.SECONDS);
		}
		catch (ExecutionException ex) {
			// the reading failed: failure() says why, and the result keeps what was read
		}
		catch (TimeoutException ex) {
			// the last reading is still running: the result leaves it out
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * The window a sampler reads through, from when the load starts.
	 *
	 * @param warmup seconds before it opens
	 * @param duration its length, a second of it lost to detecting the start
	 */
	public record Window(double warmup, double duration) {

		/** A measured step's: after k6's warm-up, its measured seconds. */
		public static final Window MEASURED = new Window(Protocol.WARMUP_S, Protocol.DURATION_S);

		/** A calibration pass's, which k6 records from its start. */
		public static final Window CALIBRATION = new Window(0, Protocol.CALIBRATION_S);

	}

}
