package io.archsim.engine.components.queue;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.Sampler;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.Outcome;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.SpanKind;
import io.archsim.engine.runtime.Timing;
import io.archsim.engine.runtime.TokenBucket;
import io.archsim.model.component.queue.MessageQueueConfig;

/**
 * A producer's publish: refused by a FIFO queue's publish limit or a full backlog (or,
 * delivered at most once, dropped), blocked while the memory alarm is up, then
 * acknowledged after its publish time, the producer's linger and the replicas' copy. The
 * producer's request succeeds once the publish is acknowledged; consumers take the
 * message later.
 */
final class Publishing {

	/** The counter of messages dropped by a full backlog. */
	static final String DROPPED = "dropped";

	/** The mean time a publisher waited for the memory alarm to clear. */
	static final String PUBLISH_BLOCKED = "publishBlocked";

	private final QueueState state;

	private final Timing timing;

	private final PythonRandom stream;

	private final Sampler publishTime;

	private final Sampler replicationTime;

	private final @Nullable TokenBucket fifoLimit;

	private final double lingerMs;

	private final boolean acknowledgedByAll;

	private final boolean dropsWhenFull;

	private final boolean alarmed;

	/**
	 * A queue's publishes.
	 * @param state the queue
	 * @param timing how it draws its times
	 * @param stream the stream its publishes draw from
	 */
	Publishing(QueueState state, Timing timing, PythonRandom stream) {
		MessageQueueConfig config = state.config();
		this.state = state;
		this.timing = timing;
		this.stream = stream;
		this.publishTime = Sampler.of(config.publishTimeMs());
		this.replicationTime = Sampler.of(config.replicationMs());
		this.fifoLimit = config.limitsFifoPublishes() ? TokenBucket.ofRate(config.fifoLimitRps()) : null;
		this.lingerMs = config.lingerMsInEffect();
		this.acknowledgedByAll = config.acknowledgedByAll();
		this.dropsWhenFull = config.deliversAtMostOnce();
		this.alarmed = config.flowThreshold().isPresent();
	}

	/**
	 * Publishes a request's message.
	 * @param request the request
	 * @param done what to do with how the publish ended
	 */
	void publish(Request request, Consumer<Outcome> done) {
		double now = now();
		metrics().arrive(now);
		if (this.fifoLimit != null && !this.fifoLimit.tryTake(now, 1.0)) {
			refuse(request, FailureReason.THROTTLED, "FIFO publish limit", done);
			return;
		}
		Integer maxDepth = this.state.config().maxDepth();
		boolean full = maxDepth != null && this.state.backlog().depth() >= maxDepth;
		if (full && !this.dropsWhenFull) {
			refuse(request, FailureReason.QUEUE_FULL, "backlog full", done);
			return;
		}
		if (!this.alarmed) {
			acknowledge(request, new Publish(now, full), done);
			return;
		}
		if (!this.state.backlog().blocked()) {
			metrics().recordMean(PUBLISH_BLOCKED, now, 0.0);
			acknowledge(request, new Publish(now, full), done);
			return;
		}
		this.state.backlog().whenUnblocked(() -> {
			metrics().recordMean(PUBLISH_BLOCKED, now(), now() - now);
			request.span(this.state.nodeId(), SpanKind.BLOCKED, new Request.Interval(now, now()),
					"memory alarm: publisher blocked");
			acknowledge(request, new Publish(now, full), done);
		});
	}

	// the publish's time, the producer's linger and the replicas' copy, then the message
	// joins the backlog (or, dropped, does not)
	private void acknowledge(Request request, Publish publish, Consumer<Outcome> done) {
		double publishMs = this.timing.sample(this.publishTime, this.stream);
		if (this.lingerMs > 0) {
			publishMs += this.stream.nextDouble() * this.lingerMs;
		}
		if (this.acknowledgedByAll) {
			publishMs += this.replicationTime.sample(this.stream);
		}
		double tookMs = publishMs;
		this.state.simulation().after(tookMs, () -> acknowledged(request, publish, tookMs, done));
	}

	private void acknowledged(Request request, Publish publish, double tookMs, Consumer<Outcome> done) {
		double now = now();
		metrics().service().record(now, tookMs);
		request.span(this.state.nodeId(), SpanKind.PUBLISH, new Request.Interval(publish.start(), now),
				this.acknowledgedByAll ? "acks=all" : null);
		if (publish.dropped()) {
			metrics().count(DROPPED, now);
		}
		else {
			this.state.backlog().add(new Message(request, now, publish.start()), now);
		}
		metrics().hold().record(now, now - publish.start());
		metrics().depart(now, Outcome.OK, request);
		done.accept(Outcome.OK);
	}

	private void refuse(Request request, FailureReason reason, String detail, Consumer<Outcome> done) {
		double now = now();
		metrics().reject(reason, now);
		request.span(this.state.nodeId(), SpanKind.REJECTED, new Request.Interval(now, now), detail);
		done.accept(Outcome.rejected(reason, this.state.nodeId()));
	}

	private NodeMetrics metrics() {
		return this.state.metrics();
	}

	private double now() {
		return this.state.simulation().now();
	}

	/**
	 * A publish under way.
	 *
	 * @param start when it started
	 * @param dropped whether the full backlog drops its message
	 */
	private record Publish(double start, boolean dropped) {
	}

}
