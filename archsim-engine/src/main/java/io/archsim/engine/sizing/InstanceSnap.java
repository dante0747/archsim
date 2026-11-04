package io.archsim.engine.sizing;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.pricing.Busy;
import io.archsim.engine.pricing.Machine;
import io.archsim.engine.pricing.MachineSize;
import io.archsim.engine.pricing.PricingChoice;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.pricing.Sku;

/**
 * Sizes with a price snapshot: CPU and memory snapped up to the cheapest instance type
 * that covers them (a spec set on the part counts as the need for that spec), a burstable
 * type when the part passes the credit check, so sizes are real machine shapes.
 */
final class InstanceSnap {

	private static final double TOLERANCE = 1e-9;

	/**
	 * A part on a type of at most this many vCPU, or the smallest, cannot shrink further.
	 */
	private static final double SMALL_TYPE_VCPU = 2.0;

	private InstanceSnap() {
	}

	/**
	 * A part's size snapped to an instance type.
	 * @param size the auto size
	 * @param nodeType the part's component type
	 * @param settings the part's settings
	 * @param pricing the design's price snapshot and purchase
	 * @return the size on an instance type; as it is, with a note, when no type covers it
	 */
	static PartSize snapped(PartSize size, String nodeType, LenientSettings settings, PricingChoice pricing) {
		SetSpecs set = SetSpecs.of(settings);
		if (set.cpuOr(size) == null && set.memoryOr(size) == null) {
			return size;
		}
		Busy busy = size.busy().orElse(null);
		Optional<Machine> machine = pricing.instanceTypes()
			.choose(nodeType, settings, new MachineSize(set.cpuOr(size), set.memoryOr(size), busy));
		if (machine.isEmpty()) {
			return size;
		}
		Sku sku = machine.get().sku();
		if (sku == null) {
			return uncovered(size, machine.get(), set, pricing);
		}
		String how = how(machine.get(), settings, busy, pricing);
		PartSize snapped = size;
		if (needsLessThanSmallest(size, machine.get(), sku, pricing)) {
			snapped = snapped.withSmallest(true);
			how += " (it needs less CPU than the smallest " + machine.get().category().json() + " type has)";
		}
		if (size.cpuCores() != null) {
			snapped = snapped.with(Spec.CPU_CORES, sku.vcpu(), "; snapped to " + how);
		}
		if (size.memoryGib() != null) {
			snapped = snapped.with(Spec.MEMORY_GIB, sku.memoryGib(), "; snapped to " + how);
		}
		return snapped.onInstanceType(sku.sku());
	}

	/**
	 * A size no type of its kind covers: kept as computed, and saying so.
	 * @param size the auto size
	 * @param machine the machine the part would run on, without a type
	 * @param set the specs set on the part
	 * @param pricing the design's price snapshot and purchase
	 * @return the size, its explanations noted
	 */
	private static PartSize uncovered(PartSize size, Machine machine, SetSpecs set, PricingChoice pricing) {
		Double cpu = set.cpuOr(size);
		Double memory = set.memoryOr(size);
		String note = "; no %s type in %s has %s vCPU and %s: kept as computed".formatted(machine.category().json(),
				pricing.snapshot().label(), SizingText.brief((cpu != null) ? cpu : 0.0),
				SizingText.gib((memory != null) ? memory : 0.0));
		PartSize noted = size;
		for (Spec spec : new Spec[] { Spec.CPU_CORES, Spec.MEMORY_GIB }) {
			if (size.why().containsKey(spec)) {
				noted = noted.explained(spec, note);
			}
		}
		return noted;
	}

	/**
	 * Which type the part got, and why: its own, or the cheapest that covers it.
	 * @param machine the machine the part runs on, with its type
	 * @param settings the part's settings
	 * @param busy the cores one instance keeps busy, or {@code null} when unknown
	 * @param pricing the design's price snapshot and purchase
	 * @return the explanation
	 */
	private static String how(Machine machine, LenientSettings settings, @Nullable Busy busy, PricingChoice pricing) {
		Sku sku = Objects.requireNonNull(machine.sku(), "a machine with a type");
		String shape = "%s (%s vCPU, %s)".formatted(sku.sku(), SizingText.plain(sku.vcpu()),
				SizingText.gib(sku.memoryGib()));
		if (machine.named()) {
			return shape + ", the instance type set on the part";
		}
		String how = "%s, the cheapest %stype in %s that covers it".formatted(shape,
				machine.selfManaged() ? "compute (self-managed) " : "", pricing.snapshot().label());
		String named = settings.text("instanceType").orElse("");
		if (!named.isEmpty()) {
			how += " (" + named + ", the type set on the part, is not in it or is too small)";
		}
		Double baseline = sku.baselineCpu();
		if (sku.burstable() && baseline != null && busy != null) {
			how += credits(busy, baseline, sku.vcpu());
		}
		return how;
	}

	private static String credits(Busy busy, double baseline, double vcpu) {
		return ("; burstable: %s cores busy at the base load stay under %s of its baseline (%s cores, %s of each "
				+ "vCPU), so it earns CPU credits, and %s at the peak stay under %s of its vCPUs")
			.formatted(SizingText.brief(busy.base()), SizingText.percent(Busy.BURST_BASE_SHARE),
					SizingText.brief(baseline * vcpu), SizingText.percent(baseline), SizingText.brief(busy.peak()),
					SizingText.percent(Busy.BURST_PEAK_SHARE));
	}

	/**
	 * Whether the part's CPU need is below the smallest type of its kind: it cannot
	 * shrink.
	 * @param size the auto size
	 * @param machine the machine the part runs on
	 * @param sku its type
	 * @param pricing the design's price snapshot and purchase
	 * @return whether it needs less than the smallest type has
	 */
	private static boolean needsLessThanSmallest(PartSize size, Machine machine, Sku sku, PricingChoice pricing) {
		Double need = size.need().get(Need.CPU_CORES);
		double smallest = pricing.snapshot()
			.skus()
			.getOrDefault(machine.category(), List.of())
			.stream()
			.filter((type) -> type.runs(machine.engine()))
			.mapToDouble(Sku::vcpu)
			.min()
			.orElse(sku.vcpu());
		return need != null && need <= Math.max(1.0, smallest) + TOLERANCE
				&& sku.vcpu() <= Math.max(smallest, SMALL_TYPE_VCPU);
	}

}
