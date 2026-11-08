package io.archsim.engine.components.queue;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.LatencyHistogram;
import io.archsim.engine.metrics.MeanAccumulator;
import io.archsim.engine.runtime.EdgeRuntime;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.Outcome;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.SimContext;
import io.archsim.engine.runtime.SpanKind;
import io.archsim.model.document.RequestType;

/**
 * What a queue's consumer does with a message: it takes it off the backlog, works on it,
 * then calls the queue's links that carry its kind of request, in order, until one fails.
 * A failed delivery goes back to the end of the backlog until the message has been
 * delivered as often as allowed, then it is dead-lettered. With a visibility timeout, a
 * message still being processed when the timeout passes reappears, and another consumer
 * takes it too.
 */
final class Deliveries {

	/** The counter of messages processed. */
	static final String PROCESSED = "processed";

	/** The counter of deliveries that failed. */
	static final String FAILED_DELIVERIES = "failedDeliveries";

	/** The counter of messages given up on after their last delivery failed. */
	static final String DEAD_LETTERED = "deadLettered";

	/** The counter of messages that reappeared after their visibility timeout. */
	static final String REDELIVERIES = "redeliveries";

	private final SimContext context;

	private final String nodeId;

	private final NodeMetrics metrics;

	private final Backlog backlog;

	private final Rules rules;

	private final Map<RequestType, List<EdgeRuntime>> links = new EnumMap<>(RequestType.class);

	private final MeanAccumulator ages;

	private final LatencyHistogram windowAges = new LatencyHistogram();

	private int processing;

	/**
	 * A queue's deliveries.
	 * @param context the run
	 * @param owner the queue
	 * @param backlog its backlog
	 * @param rules how long a consumer works on a message and how often it may be
	 * delivered
	 */
	Deliveries(SimContext context, Owner owner, Backlog backlog, Rules rules) {
		this.context = context;
		this.nodeId = owner.nodeId();
		this.metrics = owner.metrics();
		this.backlog = backlog;
		this.rules = rules;
		this.ages = new MeanAccumulator(context.timeline());
	}

	/**
	 * Delivers a message to a consumer that took it.
	 * @param message the message
	 * @param done what the consumer does next
	 */
	void process(Message message, Runnable done) {
		double now = now();
		this.backlog.taken(now);
		double waitMs = now - message.publishedAt();
		this.metrics.queueWait().record(now, waitMs);
		if (this.context.timeline().inWindow(now)) {
			this.metrics.queueWaitHistogram().record(waitMs);
		}
		this.processing++;
		this.metrics.busy().add(now, 1);
		Message.Lease lease = leaseFor(message);
		Request request = message.delivery();
		request.span(this.nodeId, SpanKind.QUEUED, new Request.Interval(message.publishedAt(), now),
				"waiting in the backlog");
		double consumeMs = this.rules.consumeMs().getAsDouble();
		this.metrics.cpu().add(now, 1);
		simulation().after(consumeMs, () -> {
			this.metrics.cpu().add(now(), -1);
			request.span(this.nodeId, SpanKind.CONSUME, new Request.Interval(now, now()), null);
			callFrom(linksFor(message.operation()), 0, request,
					(outcome) -> delivered(new Delivery(message, lease, now), outcome, done));
		});
	}

	/**
	 * Whether a consumer is processing a message now.
	 * @return {@code true} while one is
	 */
	boolean busy() {
		return this.processing > 0;
	}

	/**
	 * The mean age of the messages processed in each bucket, from the start of their
	 * publish.
	 * @return the ages
	 */
	MeanAccumulator ages() {
		return this.ages;
	}

	/**
	 * The ages of the messages processed inside the measurement window.
	 * @return their histogram
	 */
	LatencyHistogram windowAges() {
		return this.windowAges;
	}

	// with a visibility timeout, the consumer's lease, which the timeout checks
	private Message.@Nullable Lease leaseFor(Message message) {
		OptionalDouble visibilityMs = this.rules.visibilityMs();
		if (visibilityMs.isEmpty()) {
			return null;
		}
		Message.Lease lease = message.lease();
		simulation().start((finish) -> simulation().after(visibilityMs.getAsDouble(), () -> {
			if (message.leasedUnder(lease)) {
				double now = now();
				this.metrics.count(REDELIVERIES, now);
				this.backlog.add(message.reappeared(), now);
			}
			finish.run();
		}));
		return lease;
	}

	private void callFrom(List<EdgeRuntime> calls, int index, Request request, Consumer<Outcome> done) {
		if (index == calls.size()) {
			done.accept(Outcome.OK);
			return;
		}
		this.context.calls().call(calls.get(index), request, this.nodeId, (outcome) -> {
			if (!outcome.ok()) {
				done.accept(outcome);
				return;
			}
			callFrom(calls, index + 1, request, done);
		});
	}

	private void delivered(Delivery delivery, Outcome outcome, Runnable done) {
		double end = now();
		Message message = delivery.message();
		Message.Lease lease = delivery.lease();
		if (lease != null) {
			message.returnLease(lease);
		}
		this.metrics.busy().add(end, -1);
		this.metrics.hold().record(end, end - delivery.start());
		this.processing--;
		message.delivered();
		if (outcome.ok()) {
			this.metrics.count(PROCESSED, end);
			double ageMs = end - message.createdAt();
			this.ages.record(end, ageMs);
			if (this.context.timeline().inWindow(end)) {
				this.windowAges.record(ageMs);
			}
		}
		else {
			this.metrics.count(FAILED_DELIVERIES, end);
			if (message.deliveries() < this.rules.maxDeliveries()) {
				this.backlog.add(message, end);
			}
			else {
				this.metrics.count(DEAD_LETTERED, end);
			}
		}
		done.run();
	}

	// the queue's links that carry a kind of request, by their order, then their id
	private List<EdgeRuntime> linksFor(RequestType operation) {
		return this.links.computeIfAbsent(operation, (kind) -> this.context.outEdges(this.nodeId, "out")
			.stream()
			.filter((edge) -> edge.carries(kind))
			.sorted(Comparator.comparingInt((EdgeRuntime edge) -> edge.config().order()).thenComparing(EdgeRuntime::id))
			.toList());
	}

	private Simulation simulation() {
		return this.context.simulation();
	}

	private double now() {
		return this.context.simulation().now();
	}

	/**
	 * The queue deliveries belong to.
	 *
	 * @param nodeId its id
	 * @param metrics its metrics, which count its deliveries
	 */
	record Owner(String nodeId, NodeMetrics metrics) {
	}

	/**
	 * How long a consumer works on a message, how often a message may be delivered, and
	 * how long it stays hidden while a consumer holds it.
	 *
	 * @param consumeMs draws a consumer's time on a message, calls excluded
	 * @param maxDeliveries the deliveries before a failing message is dead-lettered
	 * @param visibilityMs the visibility timeout; empty without one
	 */
	record Rules(DoubleSupplier consumeMs, int maxDeliveries, OptionalDouble visibilityMs) {
	}

	/**
	 * A message a consumer holds.
	 *
	 * @param message the message
	 * @param lease the consumer's lease; {@code null} without a visibility timeout
	 * @param start when the consumer took it
	 */
	private record Delivery(Message message, Message.@Nullable Lease lease, double start) {
	}

}
