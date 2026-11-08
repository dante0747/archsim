package io.archsim.engine.runtime;

import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Resource;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.runtime.Request.Interval;

/**
 * A bounded set of slots (threads, connections, executors) with a bounded queue served in
 * order: a request that finds the slots busy and the queue full is refused at once, and
 * one that waits longer than the queue timeout gives up its place.
 */
public final class WorkerPool {

	private final Simulation simulation;

	private final Resource resource;

	private final Queue queue;

	private final NodeMetrics metrics;

	/**
	 * A pool.
	 * @param simulation the run
	 * @param slots how many requests it serves at once
	 * @param queue how many may wait, and for how long
	 * @param metrics the part's metrics, which count the queue, the waits and the busy
	 * slots
	 */
	public WorkerPool(Simulation simulation, int slots, Queue queue, NodeMetrics metrics) {
		this.simulation = simulation;
		this.resource = new Resource(simulation, slots);
		this.queue = queue;
		this.metrics = metrics;
	}

	/**
	 * Takes a slot for a request, waiting in the queue if they are all busy; a slot free
	 * at once is taken without waiting for an event.
	 * @param request the request, whose wait a trace shows
	 * @param nodeId the part the pool belongs to
	 * @param then what to do with the slot, or with why there is none
	 */
	public void acquire(Request request, String nodeId, Consumer<Acquired> then) {
		if (this.resource.count() >= this.resource.capacity() && this.resource.queued() >= this.queue.capacity()) {
			then.accept(new Refused(FailureReason.QUEUE_FULL));
			return;
		}
		Resource.Request slot = this.resource.request();
		if (slot.triggered()) {
			then.accept(taken(slot, 0.0));
			return;
		}
		double queued = this.simulation.now();
		this.metrics.queue().add(queued, 1);
		Waiting waiting = new Waiting(request, nodeId, slot, queued, then);
		Double timeoutMs = this.queue.timeoutMs();
		if (timeoutMs == null) {
			slot.then(() -> leave(waiting));
		}
		else {
			this.simulation.anyOf(List.of(slot, this.simulation.timeout(timeoutMs))).then(() -> leave(waiting));
		}
	}

	/**
	 * Gives a slot back, to the oldest request waiting for one.
	 * @param slot the slot taken
	 */
	public void release(Resource.Request slot) {
		this.resource.release(slot);
		this.metrics.busy().add(this.simulation.now(), -1);
	}

	private void leave(Waiting waiting) {
		double now = this.simulation.now();
		this.metrics.queue().add(now, -1);
		waiting.request().span(waiting.nodeId(), SpanKind.QUEUE, new Interval(waiting.queued(), now), null);
		if (!waiting.slot().triggered()) {
			waiting.slot().cancel();
			waiting.then().accept(new Refused(FailureReason.QUEUE_TIMEOUT));
			return;
		}
		waiting.then().accept(taken(waiting.slot(), now - waiting.queued()));
	}

	private Acquired taken(Resource.Request slot, double waitMs) {
		double now = this.simulation.now();
		this.metrics.queueWait().record(now, waitMs);
		if (this.metrics.timeline().inWindow(now)) {
			this.metrics.queueWaitHistogram().record(waitMs);
		}
		this.metrics.busy().add(now, 1);
		return new Taken(slot);
	}

	/**
	 * A pool's queue.
	 *
	 * @param capacity how many requests may wait
	 * @param timeoutMs how long one waits at most; {@code null} for as long as it takes
	 */
	public record Queue(int capacity, @Nullable Double timeoutMs) {
	}

	/**
	 * A slot taken, or why none was.
	 */
	public sealed interface Acquired permits Taken, Refused {

	}

	/**
	 * A slot taken.
	 *
	 * @param slot the slot, to give back when done
	 */
	public record Taken(Resource.Request slot) implements Acquired {
	}

	/**
	 * No slot.
	 *
	 * @param reason why: the queue was full, or the wait timed out
	 */
	public record Refused(FailureReason reason) implements Acquired {
	}

	/**
	 * A request waiting in the queue: it leaves with its slot when it gets one, or
	 * without when the queue timeout comes first.
	 *
	 * @param request the request
	 * @param nodeId the part the pool belongs to
	 * @param slot its place in the queue
	 * @param queued when it started waiting
	 * @param then what to do with the slot, or with why there is none
	 */
	private record Waiting(Request request, String nodeId, Resource.Request slot, double queued,
			Consumer<Acquired> then) {
	}

}
