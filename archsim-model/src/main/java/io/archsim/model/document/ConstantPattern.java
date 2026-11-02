package io.archsim.model.document;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.Tag;

/** Steady offered load at the workload's {@code rps}. */
@Tag("constant")
@Description("Steady offered load at ``workload.rps``.")
public record ConstantPattern() implements TrafficPattern {
}
