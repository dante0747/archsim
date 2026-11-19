package io.archsim.engine.sizing;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.host.HostMachine;
import io.archsim.engine.host.SiblingSpeed;
import io.archsim.engine.host.SiblingSpeeds;
import io.archsim.engine.host.VcpuBasis;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.engine.sizing.Placement.Work;
import io.archsim.engine.sizing.Steps.Explained;
import io.archsim.model.document.Architecture;

/**
 * What a part's vCPUs are, as the number it needs for its work depends on it: whole
 * cores; hyperthreads, two per physical core, which slow each other down when both are
 * busy; or, by default (ADR-0091), whichever the instance family it lands on gives.
 */
sealed interface Vcpus {

	/** vCPUs that are each a core of their own. */
	Vcpus WHOLE_CORES = new WholeCores();

	/**
	 * What a part's vCPUs are, by the rule every reader of them goes by
	 * ({@link SiblingSpeeds#beforeFamily}): hyperthreads at the part's own sibling speed,
	 * else the design's; whole cores for a serverless service, a pod and a speed of 1;
	 * else, by default, whole cores on a whole-core family's machines and pairs at the
	 * default speed on the others. On a design with a host machine every part's threads
	 * are the host's, whose speed the part's placement carries, and they count as whole
	 * cores here.
	 * @param architecture the design's architecture, for its host and its sibling speed
	 * @param nodeType the part's component type
	 * @param settings the settings of the part's config, which bound: a sibling speed in
	 * them is above 0 and at most 1
	 * @return its vCPUs
	 */
	static Vcpus of(Architecture architecture, String nodeType, LenientSettings settings) {
		return SiblingSpeeds.beforeFamily(architecture, nodeType, settings).map(Vcpus::counted).orElse(WHOLE_CORES);
	}

	private static Vcpus counted(SiblingSpeed speed) {
		if (!speed.pairs()) {
			return WHOLE_CORES;
		}
		Hyperthreads pairs = new Hyperthreads(speed.speed());
		return (speed.basis() == VcpuBasis.DEFAULT) ? new ByFamily(pairs) : pairs;
	}

	/**
	 * The vCPU a part gets for its CPU work at the peak: enough for the work to be the
	 * target share of what they can do, rounded up to a common size, and no more than its
	 * handlers can use.
	 * @param work the cores it keeps busy at the peak, and how they were worked out
	 * @param handlers the pieces of work one instance runs at once
	 * @param reason why the handlers cannot use more
	 * @return the vCPU it gets, and on each kind of vCPU where its machine decides
	 */
	SizedVcpus forWork(Work work, int handlers, String reason);

	/**
	 * The vCPU a part gets for its work.
	 *
	 * @param chosen its size where nothing more is known of its machine, as on a standard
	 * shape: on hyperthreads, pairs, unless 1 vCPU of whole cores does its work
	 * @param kinds its size on each kind of vCPU, where the machine it lands on decides;
	 * {@code null} on whole cores
	 */
	record SizedVcpus(CpuSize chosen, @Nullable CpuKinds kinds) {
	}

	/** Whole cores: a vCPU does a core's work, however many of them are busy. */
	record WholeCores() implements Vcpus {

		@Override
		public SizedVcpus forWork(Work work, int handlers, String reason) {
			Explained cpu = Steps.capped(Steps.cpu(work.busy(), work.intro()), handlers, reason);
			return new SizedVcpus(new CpuSize(cpu.value(), cpu.why(), work.busy() / Sizing.TARGET_CPU, null), null);
		}

	}

	/**
	 * Hyperthreads, two per physical core: a vCPU whose core's other vCPU is busy too
	 * does less than a core's work, so together they can do less than a core's work each.
	 * Every machine of 2 or more vCPU gives a part such vCPUs, whatever its family.
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
		 * A part that gets one vCPU on whole cores keeps it where nothing more is known
		 * of its machine: one vCPU has no sibling. On a machine of 2 or more vCPU it
		 * needs the pairs' size all the same; one handler, which never has two vCPUs
		 * busy, needs no more than on whole cores.
		 */
		@Override
		public SizedVcpus forWork(Work work, int handlers, String reason) {
			CpuSize onWholeCores = WHOLE_CORES.forWork(work, handlers, reason).chosen();
			CpuSize onPairs = (handlers == 1) ? onWholeCores : onPairs(work, handlers, reason);
			CpuSize chosen = (onWholeCores.vcpu() <= 1.0) ? onWholeCores : onPairs;
			return new SizedVcpus(chosen, new CpuKinds(onWholeCores, onPairs, false));
		}

		private CpuSize onPairs(Work work, int handlers, String reason) {
			double perVcpu = coresPerVcpu();
			double taken = work.busy() / perVcpu;
			Explained sized = Steps.atTarget(taken, explained(work, perVcpu, taken), " of what they can do");
			Explained cpu = Steps.cappedOnHyperthreads(sized, handlers, reason);
			return new CpuSize(cpu.value(), cpu.why(), taken / Sizing.TARGET_CPU, perVcpu);
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

	/**
	 * The default (ADR-0091): whole cores on a machine of a whole-core family,
	 * hyperthread pairs at the default sibling speed on the others, and pairs where
	 * nothing more is known of the machine.
	 *
	 * @param pairs the vCPUs on the machines whose vCPUs are pairs
	 */
	record ByFamily(Hyperthreads pairs) implements Vcpus {

		@Override
		public SizedVcpus forWork(Work work, int handlers, String reason) {
			SizedVcpus onPairs = this.pairs.forWork(work, handlers, reason);
			CpuKinds kinds = Objects.requireNonNull(onPairs.kinds(), "hyperthreads size both kinds");
			return new SizedVcpus(onPairs.chosen(), new CpuKinds(kinds.onWholeCores(), kinds.onPairs(), true));
		}

	}

}
