package io.archsim.engine.components.client;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.archsim.engine.metrics.BucketedHistograms;
import io.archsim.engine.metrics.Counter;
import io.archsim.engine.metrics.LatencyHistogram;
import io.archsim.engine.metrics.LatencyWithFailures;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.runtime.Outcome;
import io.archsim.engine.runtime.Request;
import io.archsim.model.document.RequestType;

/**
 * What clients experienced end to end, shared by every client group of a run: the
 * requests offered, served and failed, the attempts, the latencies (failures counted as a
 * full timeout too), by cohort and by kind of failure.
 */
public final class ClientMetrics {

	private final Counter offered;

	private final Counter succeeded;

	private final Counter errors;

	private final Counter timeouts;

	private final Counter attempts;

	private final LatencyHistogram latency = new LatencyHistogram();

	private final LatencyWithFailures latencyWithFailures = new LatencyWithFailures();

	private final Map<RequestType, LatencyHistogram> latencyByOperation = new EnumMap<>(RequestType.class);

	private final BucketedHistograms latencySeries;

	private final Cohort warmup = new Cohort();

	private final Cohort measured = new Cohort();

	private final Map<FailureKey, Long> failures = new LinkedHashMap<>();

	/**
	 * A run's client metrics, all at zero.
	 * @param timeline the run's timeline
	 */
	public ClientMetrics(Timeline timeline) {
		this.offered = new Counter(timeline);
		this.succeeded = new Counter(timeline);
		this.errors = new Counter(timeline);
		this.timeouts = new Counter(timeline);
		this.attempts = new Counter(timeline);
		this.latencySeries = new BucketedHistograms(timeline);
		this.latencyByOperation.put(RequestType.READ, new LatencyHistogram());
		this.latencyByOperation.put(RequestType.WRITE, new LatencyHistogram());
	}

	/**
	 * The cohort a user request belongs to.
	 * @param request the request
	 * @return the measured cohort for a request that arrived in the window, else the
	 * warm-up's
	 */
	Cohort cohort(Request request) {
		return request.arrival().measured() ? this.measured : this.warmup;
	}

	/**
	 * Records how a user request ended for its client.
	 * @param request the request
	 * @param outcome how it ended
	 * @param now when
	 * @param timeoutMs the client's timeout, at which a failure counts in the latencies
	 */
	void record(Request request, Outcome outcome, double now, double timeoutMs) {
		Cohort cohort = cohort(request);
		double waited = now - request.arrival().sentAt();
		boolean counted = request.arrival().measured();
		if (outcome.ok()) {
			cohort.succeed();
			this.succeeded.increment(now);
			this.latencySeries.record(now, waited);
			if (counted) {
				this.latency.record(waited);
				this.latencyWithFailures.success(waited);
				Objects.requireNonNull(this.latencyByOperation.get(request.operation())).record(waited);
			}
			return;
		}
		recordFailure(cohort, outcome, now);
		if (counted) {
			this.latencyWithFailures.failure(waited, timeoutMs);
			this.failures.merge(FailureKey.of(outcome), 1L, Long::sum);
		}
	}

	private void recordFailure(Cohort cohort, Outcome outcome, double now) {
		if (outcome.failedFor(FailureReason.CLIENT_TIMEOUT)) {
			cohort.timeOut();
			this.timeouts.increment(now);
		}
		else {
			cohort.fail();
			this.errors.increment(now);
		}
	}

	public Counter offered() {
		return this.offered;
	}

	public Counter succeeded() {
		return this.succeeded;
	}

	public Counter errors() {
		return this.errors;
	}

	public Counter timeouts() {
		return this.timeouts;
	}

	public Counter attempts() {
		return this.attempts;
	}

	public LatencyHistogram latency() {
		return this.latency;
	}

	public LatencyWithFailures latencyWithFailures() {
		return this.latencyWithFailures;
	}

	/**
	 * The latencies of the measured requests that succeeded, by kind.
	 * @return reads' and writes' histograms, reads first
	 */
	public Map<RequestType, LatencyHistogram> latencyByOperation() {
		return Collections.unmodifiableMap(this.latencyByOperation);
	}

	public BucketedHistograms latencySeries() {
		return this.latencySeries;
	}

	public Cohort warmup() {
		return this.warmup;
	}

	public Cohort measured() {
		return this.measured;
	}

	/**
	 * The measured requests that failed, by how they failed.
	 * @return each kind's count, in the order the kinds first happened
	 */
	public Map<FailureKey, Long> failures() {
		return Collections.unmodifiableMap(this.failures);
	}

}
