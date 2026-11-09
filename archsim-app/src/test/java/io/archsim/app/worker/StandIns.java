package io.archsim.app.worker;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.simulator.SimulationCancelledException;

/**
 * Stand-ins for the simulator, as the Python worker's tests had: one that reports, one
 * that throws, one that runs until asked to stop, one that will not stop.
 */
final class StandIns {

	/** A report with the parts a run's outcome reads. */
	static final JsonNode REPORT = QueueParts.JSON.readTree("""
			{"summary": {"offeredRps": 100.0, "throughputRps": 99.5, "successRate": 0.99, "timeoutRate": 0.0,
			             "errorRate": 0.01, "latencyMs": {"p50": 21.0, "p95": 30.5, "p99": 41.0}},
			 "findings": [{"kind": "hint", "title": "a hint"},
			              {"kind": "bottleneck", "title": "api is saturated", "nodeId": "api"}],
			 "provenance": {"inputDigest": "in-digest", "resultDigest": "out-digest", "wallSeconds": 0.25}}""");

	private static final long A_MOMENT = Duration.ofMillis(10).toNanos();

	private StandIns() {
	}

	/**
	 * A simulator that reports at once.
	 * @return the stand-in
	 */
	static RunSimulator reporting() {
		return (document, request, observer) -> REPORT;
	}

	/**
	 * A simulator that throws.
	 * @param failure what it throws
	 * @return the stand-in
	 */
	static RunSimulator throwing(RuntimeException failure) {
		return (document, request, observer) -> {
			throw failure;
		};
	}

	/**
	 * A simulator that reports progress until it is asked to stop, then stops as the
	 * engine does; and when its thread is interrupted, so that a test leaves none behind.
	 * @return the stand-in
	 */
	static RunSimulator untilAskedToStop() {
		return (document, request, observer) -> {
			while (!observer.cancelRequested() && !Thread.currentThread().isInterrupted()) {
				observer.progress(0.5);
				LockSupport.parkNanos(A_MOMENT);
			}
			throw new SimulationCancelledException();
		};
	}

	/**
	 * A simulator that reports no progress and runs until it is asked to stop, or its
	 * thread is interrupted: only the heartbeat can tell it another worker took its run.
	 * @return the stand-in
	 */
	static RunSimulator quietUntilAskedToStop() {
		return (document, request, observer) -> {
			while (!observer.cancelRequested() && !Thread.currentThread().isInterrupted()) {
				LockSupport.parkNanos(A_MOMENT);
			}
			throw new SimulationCancelledException();
		};
	}

	/**
	 * A simulator that does not stop when asked, only when released, then reports; or
	 * when its thread is interrupted.
	 * @param released counted down to let it end
	 * @return the stand-in
	 */
	static RunSimulator stubborn(CountDownLatch released) {
		return (document, request, observer) -> {
			while (released.getCount() > 0 && !Thread.currentThread().isInterrupted()) {
				LockSupport.parkNanos(A_MOMENT);
			}
			return REPORT;
		};
	}

}
