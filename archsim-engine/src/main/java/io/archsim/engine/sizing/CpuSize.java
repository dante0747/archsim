package io.archsim.engine.sizing;

import org.jspecify.annotations.Nullable;

/**
 * The vCPU a part gets for its CPU work at the peak, on one kind of vCPU.
 *
 * @param vcpu the vCPU
 * @param why how they were worked out
 * @param needed the vCPU the work needs at the target utilization, before rounding
 * @param siblingSpeed the share of a core each vCPU counts for, as a hyperthread whose
 * core's other vCPU is busy too; {@code null} on whole cores
 */
public record CpuSize(double vcpu, String why, double needed, @Nullable Double siblingSpeed) {

}
