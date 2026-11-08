package io.archsim.engine.components.client;

import io.archsim.engine.metrics.Counter;
import io.archsim.engine.metrics.LatencyHistogram;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.runtime.Outcome;
import io.archsim.engine.runtime.Request;

/**
 * What one client group experienced, for the report's breakdown by client: its requests,
 * attempts, how its measured requests ended and their latencies.
 */
public final class ClientGroup {

	private final Counter offered;

	private final Counter attempts;

	private final Cohort measured = new Cohort();

	private final LatencyHistogram latency = new LatencyHistogram();

	ClientGroup(Timeline timeline) {
		this.offered = new Counter(timeline);
		this.attempts = new Counter(timeline);
	}

	void record(Request request, Outcome outcome, double now) {
		if (!request.arrival().measured()) {
			return;
		}
		if (outcome.ok()) {
			this.measured.succeed();
			this.latency.record(now - request.arrival().sentAt());
		}
		else if (outcome.failedFor(FailureReason.CLIENT_TIMEOUT)) {
			this.measured.timeOut();
		}
		else {
			this.measured.fail();
		}
	}

	public Counter offered() {
		return this.offered;
	}

	public Counter attempts() {
		return this.attempts;
	}

	public Cohort measured() {
		return this.measured;
	}

	public LatencyHistogram latency() {
		return this.latency;
	}

}
