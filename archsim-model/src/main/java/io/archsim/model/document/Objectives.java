package io.archsim.model.document;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import org.jspecify.annotations.Nullable;

/**
 * Service-level objectives the design should meet. The simulation ignores them; runs are judged
 * against them. {@code null} turns a target off.
 *
 * @param latencyP95Ms the p95 end-to-end latency target, in milliseconds
 * @param latencyP99Ms the p99 end-to-end latency target, in milliseconds
 * @param availability the least share of requests that succeed
 * @param minThroughputRps the least successful requests per second
 */
@Description("""
        Service-level objectives the design should meet. Not used by the simulation itself; the UI
        and reports evaluate runs against them (pass/fail, error budget).""")
public record Objectives(
        @Default("300.0") @Description("p95 end-to-end latency target (ms)") @Gt(0) @Le(600_000) @Nullable
        Double latencyP95Ms,

        @Description("p99 end-to-end latency target (ms)") @Gt(0) @Le(600_000) @Nullable
        Double latencyP99Ms,

        @Default("0.999") @Description("Minimum share of requests that succeed") @Ge(0) @Le(1) @Nullable
        Double availability,

        @Description("Minimum successful requests/s") @Ge(0) @Le(1_000_000) @Nullable
        Double minThroughputRps) {}
