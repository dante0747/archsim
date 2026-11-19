package io.archsim.engine.sizing;

import io.archsim.engine.host.SiblingSpeed;

/**
 * How a part's vCPUs counted in a run (ADR-0091), as its sizing summary says it.
 *
 * @param siblingSpeed the speed of a vCPU whose core's other vCPU is busy too; 1 for
 * whole cores
 * @param basis why, as its JSON name: {@code part}, {@code design},
 * {@code wholeCoreFamily}, {@code serverless}, {@code pod} or {@code default}
 * @param text the same in words, such as "hyperthread pairs, each vCPU doing 0.6 of a
 * core's work while its sibling is busy too: the default, as on most x86 instances"
 */
public record VcpuSummary(double siblingSpeed, String basis, String text) {

	/**
	 * The summary of how a part's vCPUs counted.
	 * @param speed their speed and why
	 * @return the summary
	 */
	static VcpuSummary of(SiblingSpeed speed) {
		String kind = speed.pairs()
				? "hyperthread pairs, each vCPU doing %s of a core's work while its sibling is busy too"
					.formatted(SizingText.brief(speed.speed()))
				: "whole cores";
		return new VcpuSummary(speed.speed(), speed.basis().json(), kind + ": " + speed.basis().words());
	}

}
