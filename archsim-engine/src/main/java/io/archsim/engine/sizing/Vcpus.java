package io.archsim.engine.sizing;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.host.HostMachine;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.engine.sizing.Placement.Work;
import io.archsim.engine.sizing.Steps.Explained;
import io.archsim.model.document.HostSpec;

/**
 * What a part's vCPUs are, as the number it needs for its work depends on it: whole
 * cores, or hyperthreads, two per physical core, which slow each other down when both are
 * busy.
 */
sealed interface Vcpus {

	/** vCPUs that are each a core of their own. */
	Vcpus WHOLE_CORES = new WholeCores();

	/**
	 * What a part's vCPUs are: its own hyperthreads when it sets a sibling speed below 1,
	 * else whole cores. On a design with a host machine every part's threads are the
	 * host's, whose speed the part's placement carries, and its own sibling speed does
	 * not count.
	 * @param host the design's host machine; {@code null} when it has none
	 * @param settings the settings of the part's config, which bound: a sibling speed in
	 * them is above 0 and at most 1
	 * @return its vCPUs
	 */
	static Vcpus of(@Nullable HostSpec host, LenientSettings settings) {
		double siblingSpeed = settings.number("siblingSpeed").orElse(1.0);
		return (host == null && siblingSpeed < 1.0) ? new Hyperthreads(siblingSpeed) : WHOLE_CORES;
	}

	/**
	 * The vCPU a part gets for its CPU work at the peak: enough for the work to be the
	 * target share of what they can do, rounded up to a common size, and no more than its
	 * handlers can use.
	 * @param work the cores it keeps busy at the peak, and how they were worked out
	 * @param handlers the pieces of work one instance runs at once
	 * @param reason why the handlers cannot use more
	 * @return the vCPU it gets, and the vCPU it needs
	 */
	CpuSize forWork(Work work, int handlers, String reason);

	/**
	 * The vCPU a part gets for its work.
	 *
	 * @param vcpu the vCPU
	 * @param why how they were worked out
	 * @param needed the vCPU the work needs at the target utilization, before rounding
	 */
	record CpuSize(double vcpu, String why, double needed) {
	}

	/** Whole cores: a vCPU does a core's work, however many of them are busy. */
	record WholeCores() implements Vcpus {

		@Override
		public CpuSize forWork(Work work, int handlers, String reason) {
			Explained cpu = Steps.capped(Steps.cpu(work.busy(), work.intro()), handlers, reason);
			return new CpuSize(cpu.value(), cpu.why(), work.busy() / Sizing.TARGET_CPU);
		}

	}

	/**
	 * Hyperthreads, two per physical core: a vCPU whose core's other vCPU is busy too
	 * does less than a core's work, so together they can do less than a core's work each.
	 *
	 * @param siblingSpeed the speed of a vCPU whose core's other vCPU is busy too
	 */
	record Hyperthreads(double siblingSpeed) implements Vcpus {

		public Hyperthreads {
			if (!(siblingSpeed > 0.0 && siblingSpeed < 1.0)) {
				throw new IllegalArgumentException(
						"hyperthreads have a sibling speed above 0 and below 1, not " + siblingSpeed);
			}
		}

		/**
		 * The vCPU of the whole-core rule for the vCPUs the work takes with all of them
		 * busy: the same target, sizes and slack, and a cap of two vCPU for each handler.
		 * A part that gets one vCPU on whole cores keeps it: one vCPU has no sibling, and
		 * one handler never has two vCPUs busy.
		 */
		@Override
		public CpuSize forWork(Work work, int handlers, String reason) {
			CpuSize onWholeCores = WHOLE_CORES.forWork(work, handlers, reason);
			if (onWholeCores.vcpu() <= 1.0) {
				return onWholeCores;
			}
			double perVcpu = coresPerVcpu();
			double taken = work.busy() / perVcpu;
			Explained sized = Steps.atTarget(taken, explained(work, perVcpu, taken), " of what they can do");
			Explained cpu = Steps.cappedOnHyperthreads(sized, handlers, reason);
			return new CpuSize(cpu.value(), cpu.why(), taken / Sizing.TARGET_CPU);
		}

		/**
		 * The share of a core's work each vCPU counts for: its speed with every vCPU
		 * busy, by their machine's formula, which a load that fills them reaches and
		 * their CPU capacity counts on. Below a sibling speed of a half one busy vCPU on
		 * each core would do more, but a run under load does not keep to that. Any number
		 * of pairs gives the same.
		 * @return the sibling speed
		 */
		double coresPerVcpu() {
			return HostMachine.ownHyperthreads(2.0, this.siblingSpeed).speedWithEveryThreadBusy();
		}

		/**
		 * How the vCPUs a part's work takes were worked out.
		 * @param work the cores it keeps busy at the peak, and how they were worked out
		 * @param perVcpu the share of a core's work each vCPU counts for
		 * @param taken the vCPUs that do the work when each does that much
		 * @return the explanation so far
		 */
		private static String explained(Work work, double perVcpu, double taken) {
			return ("%s = %s cores busy, which is what %s vCPU can do as hyperthreads, each counting as %s of a core "
					+ "(the sibling speed)")
				.formatted(work.intro(), SizingText.brief(work.busy()), SizingText.brief(taken),
						SizingText.brief(perVcpu));
		}

	}

}
