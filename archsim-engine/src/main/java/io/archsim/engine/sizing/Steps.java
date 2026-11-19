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
		return atTarget(busy, "%s = %s cores busy".formatted(intro, SizingText.brief(busy)), "");
	}

	/**
	 * The vCPU for a part's work at the peak, counted in the vCPUs that do it, each doing
	 * the share of a core's work it counts for (on whole cores, a core: the cores it
	 * keeps busy): at the target utilization, rounded up to a common size, at least 1. A
	 * part whose work is up to 65% of what the size below can do keeps that size, rather
	 * than twice the cores for a few points.
	 * @param taken the vCPUs the work takes
	 * @param soFar how they were worked out, which the explanation starts with
	 * @param ofWhat what the work's share of the size below is a share of, in words that
	 * follow the figure, after a space of their own (" of what they can do"); empty on
	 * whole cores, where it is the share of them busy
	 * @return the vCPU and why
	 */
	static Explained atTarget(double taken, String soFar, String ofWhat) {
		double needed = taken / Sizing.TARGET_CPU;
		double cpu = Math.min(Sizing.MAX_CPU, up(Math.max(needed, 0.0), Sizing.CPU_STEPS));
		String why = "%s; at a %s target that is %s → %s vCPU".formatted(soFar, SizingText.percent(Sizing.TARGET_CPU),
				SizingText.brief(needed), SizingText.plain(cpu));
		Double below = Sizing.CPU_STEPS.stream().filter((step) -> step < cpu).max(Double::compare).orElse(null);
		if (below != null && taken <= Sizing.CPU_STEP_SLACK * below + TOLERANCE) {
			String instead = "; %s vCPU instead, since it runs the peak at %s%s (up to %s rather than double the cores)"
				.formatted(SizingText.plain(below), SizingText.percent(taken / below), ofWhat,
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
	 * The vCPU of a part on hyperthreads capped at two for each handler: every handler
	 * then has a physical core to itself, and more cores would idle. From two handlers
	 * on: one handler never has two vCPUs busy, is capped as on whole cores, and is left
	 * as it is here.
	 * @param cpu the vCPU and why
	 * @param handlers the requests one instance runs at once
	 * @param reason why the handlers cannot use more
	 * @return the vCPU, capped, and why
	 */
	static Explained cappedOnHyperthreads(Explained cpu, int handlers, String reason) {
		int most = 2 * handlers;
		if (handlers >= 2 && cpu.value() > most) {
			return new Explained(most, "%s; capped at %d, the hyperthreads of %d cores, because %s".formatted(cpu.why(),
					most, handlers, reason));
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
