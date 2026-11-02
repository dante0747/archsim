package io.archsim.model.document;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Tag;

/**
 * Offered load that ramps linearly from {@code startRps} to {@code endRps} over the run.
 *
 * @param startRps requests per second at the start
 * @param endRps requests per second at the end
 */
@Tag("ramp")
@Description("Offered load ramps linearly from ``startRps`` to ``endRps`` over the run.")
public record RampPattern(@Ge(0) @Le(100_000) double startRps,
		@Ge(0) @Le(100_000) double endRps) implements TrafficPattern {
}
