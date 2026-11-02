package io.archsim.model.document;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;

/**
 * Service-level objectives the design should meet. The simulation ignores them; runs are
 * judged against them. {@code null} turns a target off.
 *
 * @param latencyP95Ms the p95 end-to-end latency target, in milliseconds
 * @param latencyP99Ms the p99 end-to-end latency target, in milliseconds
 * @param availability the least share of requests that succeed
 * @param minThroughputRps the least successful requests per second
 */
@Description("""
		Service-level objectives the design should meet. Not used by the simulation itself; the UI
		and reports evaluate runs against them (pass/fail, error budget).""")
public record Objectives(@Default("300.0") @Description(LATENCY_P95) @Gt(0) @Le(600_000) @Nullable Double latencyP95Ms,
		@Description(LATENCY_P99) @Gt(0) @Le(600_000) @Nullable Double latencyP99Ms,
		@Default("0.999") @Description(AVAILABILITY) @Ge(0) @Le(1) @Nullable Double availability,
		@Description(MIN_THROUGHPUT) @Ge(0) @Le(1_000_000) @Nullable Double minThroughputRps) {

	static final String LATENCY_P95 = "p95 end-to-end latency target (ms)";

	static final String LATENCY_P99 = "p99 end-to-end latency target (ms)";

	static final String AVAILABILITY = "Minimum share of requests that succeed";

	static final String MIN_THROUGHPUT = "Minimum successful requests/s";

}
