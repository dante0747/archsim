package io.archsim.engine.sizing;

import java.util.List;

/**
 * Rounding a need up to a common size: the vCPU for the busy cores at the target
 * utilization, memory and disk.
 */
final class Steps {

	private static final double TOLERANCE = 1e-9;

	private Steps() {
	}

	/**
	 * The vCPU for the cores a part keeps busy at the peak: at the target utilization,
	 * rounded up to a common size, at least 1. A part that runs the peak at up to 65% of
	 * the size below keeps that size, rather than twice the cores for a few points.
	 * @param busy the cores busy at the peak
	 * @param intro how the busy cores were worked out, which the explanation starts with
	 * @return the vCPU and why
	 */
	static Explained cpu(double busy, String intro) {
		double needed = busy / Sizing.TARGET_CPU;
		double cpu = Math.min(Sizing.MAX_CPU, up(Math.max(needed, 0.0), Sizing.CPU_STEPS));
		String why = "%s = %s cores busy; at a %s target that is %s → %s vCPU".formatted(intro, SizingText.brief(busy),
				SizingText.percent(Sizing.TARGET_CPU), SizingText.brief(needed), SizingText.plain(cpu));
		Double below = Sizing.CPU_STEPS.stream().filter((step) -> step < cpu).max(Double::compare).orElse(null);
		if (below != null && busy <= Sizing.CPU_STEP_SLACK * below + TOLERANCE) {
			String instead = "; %s vCPU instead, since it runs the peak at %s (up to %s rather than double the cores)"
				.formatted(SizingText.plain(below), SizingText.percent(busy / below),
						SizingText.percent(Sizing.CPU_STEP_SLACK));
			return new Explained(below, why + instead);
		}
		return new Explained(cpu, why);
	}

	/**
	 * The vCPU capped at what a replica's handlers can use: more cores would idle. Capped
	 * exactly, not at a smaller common size, which would make the CPU the bottleneck
	 * below the handlers.
	 * @param cpu the vCPU and why
	 * @param handlers the requests one instance runs at once
	 * @param reason why the handlers cannot use more
	 * @return the vCPU, capped, and why
	 */
	static Explained capped(Explained cpu, int handlers, String reason) {
		if (handlers >= 1 && cpu.value() > handlers) {
			return new Explained(handlers, "%s; capped at %d because %s".formatted(cpu.why(), handlers, reason));
		}
		return cpu;
	}

	/**
	 * Memory for a need, rounded up to a common size.
	 * @param need the memory needed, in GiB
	 * @param floor the least memory
	 * @return the memory, in GiB
	 */
	static double memory(double need, double floor) {
		return Math.min(Sizing.MAX_MEMORY_GIB, up(Math.max(need, floor), Sizing.MEMORY_STEPS));
	}

	/**
	 * Disk for a need, rounded up to a common size.
	 * @param need the disk needed, in GiB
	 * @return the disk, in GiB
	 */
	static double disk(double need) {
		return Math.min(Sizing.MAX_STORAGE_GIB, up(Math.max(need, Sizing.MIN_STORAGE_GIB), Sizing.DISK_STEPS));
	}

	/**
	 * The first step at least {@code value}; beyond the last, a multiple of it.
	 * @param value the need
	 * @param steps the common sizes, smallest first
	 * @return the size
	 */
	static double up(double value, List<Double> steps) {
		for (double step : steps) {
			if (step >= value - TOLERANCE) {
				return step;
			}
		}
		double last = steps.getLast();
		return Math.ceil(value / last) * last;
	}

	/**
	 * A size and how it was worked out.
	 *
	 * @param value the size
	 * @param why the explanation
	 */
	record Explained(double value, String why) {
	}

}
