package io.archsim.engine.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.metrics.BucketedHistograms;
import io.archsim.engine.metrics.Counter;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.metrics.LatencyHistogram;
import io.archsim.engine.metrics.MeanAccumulator;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.report.FailureReason;

/**
 * What every part measures. At the end of a run each part's requests add up:
 * {@code arrivals == completed + failedDownstream + rejected + inProgress}.
 */
public final class NodeMetrics {

	/** The counter of a part's lookups that hit, which its hit ratio reads. */
	public static final String HITS = "hits";

	/** The counter of a part's lookups that missed, which its hit ratio reads. */
	public static final String MISSES = "misses";

	/** The counter of requests a part finished after their client gave up. */
	private static final String WASTED = "wasted";

	private final Timeline timeline;

	private final OptionalInt slots;

	private final Counter arrivals;

	private final Counter completed;

	private final Counter failedDownstream;

	private final Map<FailureReason, Counter> rejected = new LinkedHashMap<>();

	private final Gauge queue;

	private final Gauge busy;

	private final Gauge cpu;

	private final Gauge inflight;

	private final MeanAccumulator queueWait;

	private final LatencyHistogram queueWaitHistogram = new LatencyHistogram();

	private final MeanAccumulator hold;

	private final MeanAccumulator service;

	private final BucketedHistograms residence;

	private final Map<String, Counter> counters = new LinkedHashMap<>();

	/**
	 * A part's metrics, all at zero.
	 * @param timeline the run's timeline
	 * @param slots how many slots the part has; empty when it has none
	 */
	public NodeMetrics(Timeline timeline, OptionalInt slots) {
		this.timeline = timeline;
		this.slots = slots;
		this.arrivals = new Counter(timeline);
		this.completed = new Counter(timeline);
		this.failedDownstream = new Counter(timeline);
		this.queue = new Gauge(timeline);
		this.busy = new Gauge(timeline);
		this.cpu = new Gauge(timeline);
		this.inflight = new Gauge(timeline);
		this.queueWait = new MeanAccumulator(timeline);
		this.hold = new MeanAccumulator(timeline);
		this.service = new MeanAccumulator(timeline);
		this.residence = new BucketedHistograms(timeline);
	}

	/**
	 * A request arrives.
	 * @param time when
	 */
	public void arrive(double time) {
		this.arrivals.increment(time);
		this.inflight.add(time, 1);
	}

	/**
	 * A request leaves, served or failed downstream.
	 * @param time when
	 * @param outcome how it ended
	 * @param request the request, to count work finished after its client gave up;
	 * {@code null} for a client's own request
	 */
	public void depart(double time, Outcome outcome, @Nullable Request request) {
		this.inflight.add(time, -1);
		if (!outcome.ok()) {
			this.failedDownstream.increment(time);
			return;
		}
		this.completed.increment(time);
		if (request != null && request.abandoned()) {
			count(WASTED, time);
		}
	}

	/**
	 * A request is refused.
	 * @param reason why
	 * @param time when
	 */
	public void reject(FailureReason reason, double time) {
		this.rejected.computeIfAbsent(reason, (ignored) -> new Counter(this.timeline)).increment(time);
		this.inflight.add(time, -1);
	}

	/**
	 * Counts something the part does, under the name the report shows.
	 * @param name such as {@code reads} or {@code throttled}
	 * @param time when
	 */
	public void count(String name, double time) {
		this.counters.computeIfAbsent(name, (ignored) -> new Counter(this.timeline)).increment(time);
	}

	/**
	 * Integrates the gauges up to the end of the run.
	 * @param end the run's end
	 */
	public void finish(double end) {
		this.queue.finish(end);
		this.busy.finish(end);
		this.cpu.finish(end);
		this.inflight.finish(end);
	}

	/**
	 * How many requests were refused, for every reason.
	 * @return the total
	 */
	public long rejectedTotal() {
		return this.rejected.values().stream().mapToLong(Counter::total).sum();
	}

	public Timeline timeline() {
		return this.timeline;
	}

	public OptionalInt slots() {
		return this.slots;
	}

	public Counter arrivals() {
		return this.arrivals;
	}

	public Counter completed() {
		return this.completed;
	}

	public Counter failedDownstream() {
		return this.failedDownstream;
	}

	/**
	 * The refusals, by reason.
	 * @return each reason's counter, in the order the reasons first happened
	 */
	public Map<FailureReason, Counter> rejected() {
		return Collections.unmodifiableMap(this.rejected);
	}

	public Gauge queue() {
		return this.queue;
	}

	public Gauge busy() {
		return this.busy;
	}

	public Gauge cpu() {
		return this.cpu;
	}

	public Gauge inflight() {
		return this.inflight;
	}

	public MeanAccumulator queueWait() {
		return this.queueWait;
	}

	public LatencyHistogram queueWaitHistogram() {
		return this.queueWaitHistogram;
	}

	public MeanAccumulator hold() {
		return this.hold;
	}

	public MeanAccumulator service() {
		return this.service;
	}

	/**
	 * How long calls spent at the part, its downstream calls included, per bucket.
	 * @return the histograms
	 */
	public BucketedHistograms residence() {
		return this.residence;
	}

	/**
	 * What the part counted of its own, by name.
	 * @return each name's counter, in the order the names first came up
	 */
	public Map<String, Counter> counters() {
		return Collections.unmodifiableMap(this.counters);
	}

}
