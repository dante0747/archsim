package io.archsim.engine.components.service;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.Sampler;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.Request.Interval;
import io.archsim.engine.runtime.SpanKind;
import io.archsim.model.component.service.GcPauses;
import io.archsim.model.distribution.LogNormal;

/**
 * A service's garbage collector: every so many requests a replica serves, it stops the
 * replica for a sampled pause, and CPU work on the replica waits for a pause under way or
 * loses the duration of one that starts while it runs.
 */
final class GarbageCollector {

	/** The counter of stop-the-world pauses. */
	static final String PAUSES = "gcPauses";

	/** A pause's 95th percentile, in medians. */
	private static final double PAUSE_P95 = 2.5;

	private static final String DETAIL = "stop-the-world garbage collection";

	private final Simulation simulation;

	private final ServiceState state;

	private final @Nullable Schedule schedule;

	private final PythonRandom random;

	/**
	 * A service's collector.
	 * @param simulation the run
	 * @param state the service's state, where its pauses are counted
	 * @param random the stream its pauses are drawn from
	 */
	GarbageCollector(Simulation simulation, ServiceState state, PythonRandom random) {
		this.simulation = simulation;
		this.state = state;
		GcPauses gc = state.gc();
		this.schedule = (gc != null)
				? new Schedule(gc.everyRequests(), Sampler.of(new LogNormal(gc.pauseMs(), gc.pauseMs() * PAUSE_P95)))
				: null;
		this.random = random;
	}

	/**
	 * Runs CPU work on a replica's cores, after any pause under way, and stalled by a
	 * pause that starts while it runs.
	 * @param request the request, whose stalls a trace shows
	 * @param replica the replica
	 * @param workMs the work at full speed
	 * @param then what to do when it is done
	 */
	void cpu(Request request, Replica replica, double workMs, Runnable then) {
		double now = this.simulation.now();
		if (replica.pausedUntil() > now) {
			this.simulation.timeout(replica.pausedUntil() - now).then(() -> {
				request.span(this.state.nodeId(), SpanKind.GC, new Interval(now, this.simulation.now()), DETAIL);
				run(request, replica, workMs, then);
			});
			return;
		}
		run(request, replica, workMs, then);
	}

	/**
	 * Counts a request a replica served, and stops the replica after every so many, once
	 * any pause under way has ended.
	 * @param replica the replica
	 */
	void served(Replica replica) {
		Schedule pauses = this.schedule;
		if (pauses == null) {
			return;
		}
		if (replica.countServed() % pauses.everyRequests() != 0) {
			return;
		}
		double now = this.simulation.now();
		double durationMs = pauses.pause().sample(this.random);
		replica.pause(Math.max(now, replica.pausedUntil()), durationMs);
		this.state.metrics().count(PAUSES, now);
		this.state.addPause(now, durationMs);
	}

	private void run(Request request, Replica replica, double workMs, Runnable then) {
		double started = this.simulation.now();
		replica.cores().run(workMs, () -> {
			double now = this.simulation.now();
			if (started < replica.pauseStart() && replica.pauseStart() < now) {
				lose(request, replica, now, then);
				return;
			}
			then.run();
		});
	}

	// a pause started while the work ran: it lost the pause's duration
	private void lose(Request request, Replica replica, double now, Runnable then) {
		this.simulation.timeout(replica.pauseEnd() - replica.pauseStart()).then(() -> {
			request.span(this.state.nodeId(), SpanKind.GC, new Interval(now, this.simulation.now()), DETAIL);
			then.run();
		});
	}

	/**
	 * When a replica pauses, and for how long.
	 *
	 * @param everyRequests the requests a replica serves between pauses
	 * @param pause a pause's duration
	 */
	private record Schedule(int everyRequests, Sampler pause) {
	}

}
