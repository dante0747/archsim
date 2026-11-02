package io.archsim.model.document;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Tag;

/**
 * The workload's {@code rps}, with one burst at {@code burstRps} for {@code durationS}
 * seconds.
 *
 * @param burstRps requests per second during the burst
 * @param startS when the burst starts, in seconds from the start of the run
 * @param durationS how long it lasts, in seconds
 */
@Tag("burst")
@Description("Baseline ``workload.rps`` with one burst at ``burstRps`` for ``durationS`` seconds.")
public record BurstPattern(@Ge(0) @Le(100_000) double burstRps, @Ge(0) @Le(3600) double startS,
		@Gt(0) @Le(3600) double durationS) implements TrafficPattern {

	/** Whether the burst is on at {@code t} seconds. */
	public boolean active(double t) {
		return startS <= t && t < startS + durationS;
	}
}
