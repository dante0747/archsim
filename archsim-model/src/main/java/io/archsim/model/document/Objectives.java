package io.archsim.model.document;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.OmitWhenDefault;

/**
 * Service-level objectives the design should meet. The simulation ignores them; runs are
 * judged against them. {@code null} turns a target off.
 *
 * @param latencyP95Ms the p95 end-to-end latency target, in milliseconds
 * @param latencyP99Ms the p99 end-to-end latency target, in milliseconds
 * @param availability the least share of requests that succeed
 * @param minThroughputRps the least successful requests per second
 * @param messageAgeP95Ms the p95 age of a message when a consumer has processed it, at
 * the design's slowest queue, in milliseconds (schema 1.10, ADR-0084)
 */
@Description("""
		Service-level objectives the design should meet. Not used by the simulation itself; the UI
		and reports evaluate runs against them (pass/fail, error budget).""")
public record Objectives(
		@Default("300.0") @Description(Descriptions.OBJECTIVES_LATENCY_P95_MS) @Gt(0) @Le(600_000) @Nullable Double latencyP95Ms,
		@Description(Descriptions.OBJECTIVES_LATENCY_P99_MS) @Gt(0) @Le(600_000) @Nullable Double latencyP99Ms,
		@Default("0.999") @Description(Descriptions.OBJECTIVES_AVAILABILITY) @Ge(0) @Le(1) @Nullable Double availability,
		@Description(Descriptions.OBJECTIVES_MIN_THROUGHPUT_RPS) @Ge(0) @Le(1_000_000) @Nullable Double minThroughputRps,
		@OmitWhenDefault @Description(Descriptions.OBJECTIVES_MESSAGE_AGE_P95_MS) @Gt(0) @Le(86_400_000) @Nullable Double messageAgeP95Ms) {

}
