package io.archsim.app.calibration;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.web.ApiException;
import io.archsim.engine.calibration.ReplaySimulator;
import io.archsim.engine.simulation.SimulationLimits;

/**
 * A calibration's replays on platform threads of their own (ADR-0054). The request's
 * virtual thread waits for its replay instead of simulating, so a replay, which never
 * blocks, never holds a carrier the API's other requests need. As many threads as the
 * processors less one, at least one; replays beyond them wait in line.
 */
final class ReplayPool implements ReplaySimulator, AutoCloseable {

	/** What a request stopped while its replay runs is answered. */
	private static final String STOPPING = "The server is stopping: calibrate again in a moment";

	private final ReplaySimulator replay;

	private final ExecutorService threads;

	/**
	 * Replays on a pool of daemon platform threads named {@code archsim-replay-N}.
	 * @param replay the replay each thread runs
	 * @param threads how many replays run at once
	 */
	ReplayPool(ReplaySimulator replay, int threads) {
		this.replay = replay;
		this.threads = Executors.newFixedThreadPool(threads,
				Thread.ofPlatform().name("archsim-replay-", 1).daemon(true).factory());
	}

	/**
	 * How many replays run at once on a machine: one core is left to the API's other
	 * requests.
	 * @param processors the processors the JVM sees
	 * @return the processors less one, at least one
	 */
	static int threadsFor(int processors) {
		return Math.max(1, processors - 1);
	}

	/**
	 * Replays a design on a thread of the pool and waits for it.
	 * @throws ApiException {@code 503} when the request is stopped while it waits; it
	 * keeps its interrupt
	 */
	@Override
	public JsonNode simulate(ObjectNode document, @Nullable SimulationLimits limits) {
		Future<JsonNode> replayed = this.threads.submit(() -> this.replay.simulate(document, limits));
		try {
			return replayed.get();
		}
		catch (ExecutionException ex) {
			throw asThrown(ex.getCause());
		}
		catch (InterruptedException ex) {
			replayed.cancel(true);
			Thread.currentThread().interrupt();
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, STOPPING, List.of());
		}
	}

	/**
	 * Stops the threads: a replay still running is asked to stop, and its result is
	 * thrown away.
	 */
	@Override
	public void close() {
		this.threads.shutdownNow();
	}

	/**
	 * What the replay threw, to throw again as it is, so a calibration's refusals keep
	 * their words.
	 * @param thrown the cause the replay's future failed with
	 * @return the replay's own exception, or one that wraps a checked cause
	 * @throws Error the replay's error, as it is
	 */
	private static RuntimeException asThrown(@Nullable Throwable thrown) {
		if (thrown instanceof RuntimeException failure) {
			return failure;
		}
		if (thrown instanceof Error error) {
			throw error;
		}
		return new IllegalStateException("the replay failed", thrown);
	}

}
