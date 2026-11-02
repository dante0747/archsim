package io.archsim.model.document;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;
import org.jspecify.annotations.Nullable;

/**
 * A fault injected during the run (a chaos experiment): an outage refuses every call to a
 * node, a slowdown multiplies its service times, and latency adds to every traversal of
 * an edge.
 *
 * @param id unique among the workload's faults
 * @param kind what the fault does
 * @param target a node id (outage, slowdown) or an edge id (latency)
 * @param startS when it starts, in simulated seconds
 * @param durationS how long it lasts, in seconds
 * @param factor slowdown: the service-time multiplier
 * @param extraMs latency: the one-way latency added, in milliseconds
 * @param label the display name
 */
@Description("""
		A fault injected during the run (chaos experiment).

		* ``outage``   - the target node rejects every request arriving in the window ("outage").
		* ``slowdown`` - service times sampled at the target node are multiplied by ``factor``.
		* ``latency``  - each traversal of the target edge gets ``extraMs`` added (one way).""")
public record Fault(@Matches(Patterns.ID) String id, FaultKind kind,

		@Description("Node id (outage, slowdown) or edge id (latency)") @MinLength(1) @MaxLength(64) String target,

		@Description("When the fault starts (s, simulated time)") @Ge(0) @Le(3600) double startS,

		@Description("How long the fault lasts (s)") @Gt(0) @Le(3600) double durationS,

		@Default("4.0") @Description("slowdown: service-time multiplier") @Ge(1) @Le(100) double factor,

		@Default("200.0") @Description("latency: added one-way latency (ms)") @Ge(0) @Le(60_000) double extraMs,

		@MaxLength(120) @Nullable String label) {

	/** Whether the fault is on at {@code t} seconds. */
	public boolean active(double t) {
		return startS <= t && t < startS + durationS;
	}
}
