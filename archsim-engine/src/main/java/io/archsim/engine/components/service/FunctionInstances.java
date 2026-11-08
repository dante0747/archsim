package io.archsim.engine.components.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.runtime.TokenBucket;
import io.archsim.model.component.service.ServiceConfig;

/**
 * A serverless function's instances during a run, each serving one request at a time. An
 * instance that finished a request waits, warm, for the next; the one that finished last
 * is reused first. An instance idle longer than the keep-warm time is reclaimed, when a
 * request arrives and every so often. A request that finds none idle starts one, within
 * the concurrency limit and the scale-out rate; otherwise it is throttled.
 */
final class FunctionInstances {

	/** A function's scale-out rate is the instances it may start in this many seconds. */
	private static final double SCALE_OUT_SECONDS = 10.0;

	private static final double MS_PER_SECOND = 1000.0;

	/**
	 * The idle instances are checked for reclaiming this many times per keep-warm time.
	 */
	private static final double CHECKS_PER_KEEP_WARM = 10.0;

	/** The shortest time between two checks of the idle instances. */
	private static final double MIN_CHECK_PERIOD_MS = 1000.0;

	/** The longest time between two checks of the idle instances. */
	private static final double MAX_CHECK_PERIOD_MS = 30_000.0;

	private final Simulation simulation;

	private final Deque<Idle> idle = new ArrayDeque<>();

	private final Gauge instances;

	private final TokenBucket scaleOut;

	private final int limit;

	private final double keepWarmMs;

	private int alive;

	/**
	 * A function with no instance.
	 * @param simulation the run
	 * @param timeline its timeline
	 * @param config the function's settings
	 */
	FunctionInstances(Simulation simulation, Timeline timeline, ServiceConfig config) {
		this.simulation = simulation;
		this.instances = new Gauge(timeline);
		this.scaleOut = TokenBucket.of(config.scaleOutRate() / SCALE_OUT_SECONDS, (double) config.scaleOutRate());
		this.limit = config.concurrencyLimit();
		this.keepWarmMs = config.keepWarmS() * MS_PER_SECOND;
	}

	/**
	 * Starts reclaiming the idle instances every so often, for the rest of the run.
	 */
	void startReclaiming() {
		double periodMs = Math.max(MIN_CHECK_PERIOD_MS,
				Math.min(this.keepWarmMs / CHECKS_PER_KEEP_WARM, MAX_CHECK_PERIOD_MS));
		this.simulation.start((finish) -> reclaimAfter(periodMs));
	}

	/**
	 * An instance for a request, once the instances idle too long are reclaimed: the one
	 * that finished a request last, else a new one when the concurrency limit and the
	 * scale-out rate allow it.
	 * @param now the time
	 * @return the instance; empty when the request is throttled
	 */
	Optional<Instance> take(double now) {
		reclaim(now);
		Idle warmest = this.idle.pollLast();
		if (warmest != null) {
			return Optional.of(new Instance(warmest.born(), false));
		}
		if (this.alive < this.limit && this.scaleOut.tryTake(now, 1.0)) {
			this.alive++;
			this.instances.add(now, 1);
			return Optional.of(new Instance(now, true));
		}
		return Optional.empty();
	}

	/**
	 * Keeps an instance that finished a request warm, for the next.
	 * @param instance the instance
	 * @param now the time it finished
	 */
	void release(Instance instance, double now) {
		this.idle.addLast(new Idle(now, instance.born()));
	}

	/**
	 * The instances alive now, idle or serving.
	 * @return how many
	 */
	int alive() {
		return this.alive;
	}

	/**
	 * The most instances alive at once.
	 * @return the peak
	 */
	long peak() {
		return this.instances.runPeak();
	}

	/**
	 * The instances alive on average over the measurement window.
	 * @return the mean
	 */
	double windowMean() {
		return this.instances.windowMean();
	}

	/**
	 * The instances alive in each bucket, over the part of the bucket the run simulated.
	 * @return one mean per bucket; {@code null} for a bucket the run never reached
	 */
	List<@Nullable Double> countSeries() {
		return this.instances.meanSeriesUntilEnd();
	}

	/**
	 * Ends the instances' series at the end of the run.
	 * @param endMs when the run stopped
	 */
	void finish(double endMs) {
		this.instances.finish(endMs);
	}

	private void reclaimAfter(double periodMs) {
		this.simulation.after(periodMs, () -> {
			reclaim(this.simulation.now());
			reclaimAfter(periodMs);
		});
	}

	// the instances idle longer than the keep-warm time, the longest idle first
	private void reclaim(double now) {
		int reclaimed = 0;
		while (!this.idle.isEmpty() && now - this.idle.getFirst().since() > this.keepWarmMs) {
			this.idle.removeFirst();
			reclaimed++;
		}
		if (reclaimed > 0) {
			this.alive -= reclaimed;
			this.instances.add(now, -reclaimed);
		}
	}

	/**
	 * An instance serving a request.
	 *
	 * @param born when it started; one started at the very start of the run counts as
	 * warm
	 * @param cold whether it was started for this request, which waits for its cold start
	 */
	record Instance(double born, boolean cold) {
	}

	/**
	 * An idle instance.
	 *
	 * @param since when it finished its last request
	 * @param born when it started
	 */
	private record Idle(double since, double born) {
	}

}
