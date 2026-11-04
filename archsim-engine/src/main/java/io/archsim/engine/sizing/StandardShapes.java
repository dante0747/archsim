package io.archsim.engine.sizing;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.pricing.MachineKind;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.pricing.PriceSnapshot;

/**
 * Sizes without a price snapshot's machine shapes: CPU and memory rounded up together to
 * a standard shape of 2, 4 or 8 GiB per vCPU (the compute, general-purpose and
 * memory-optimised ratios every large cloud sells), the cheapest at generic rates, so a
 * part never asks for "4 vCPU, 0.5 GiB". Parts that do not run on machines of their own
 * keep their size.
 */
final class StandardShapes {

	private static final double TOLERANCE = 1e-9;

	private static final double VCPU_HOUR = Objects.requireNonNull(PriceSnapshot.GENERIC.rates().get("vcpuHour"));

	private static final double GIB_HOUR = Objects.requireNonNull(PriceSnapshot.GENERIC.rates().get("gibHour"));

	private StandardShapes() {
	}

	/**
	 * A part's size rounded up to a standard shape; a spec set on the part counts as the
	 * need for that spec and is kept.
	 * @param size the auto size
	 * @param nodeType the part's component type
	 * @param settings the part's settings
	 * @return the size on a standard shape, or as it is
	 */
	static PartSize shaped(PartSize size, String nodeType, LenientSettings settings) {
		if (MachineKind.of(nodeType, settings).isEmpty()) {
			return size;
		}
		SetSpecs set = SetSpecs.of(settings);
		Double cpu = set.cpuOr(size);
		Double memory = set.memoryOr(size);
		if (cpu == null || memory == null || set.both()) {
			return size;
		}
		Optional<Shape> shape = cheapest(cpu, memory, set);
		if (shape.isEmpty() || shape.get().equals(new Shape(cpu, memory))) {
			return size;
		}
		return roundedUp(size, shape.get(), set);
	}

	private static PartSize roundedUp(PartSize size, Shape shape, SetSpecs set) {
		String how = "; rounded up to a standard shape, %s vCPU and %s (%s GiB per vCPU)".formatted(
				SizingText.plain(shape.vcpu()), SizingText.gib(shape.gib()),
				SizingText.plain(shape.gib() / shape.vcpu()));
		PartSize rounded = size;
		if (set.cpu() == null && size.cpuCores() != null && shape.vcpu() != size.cpuCores()) {
			rounded = rounded.with(Spec.CPU_CORES, shape.vcpu(), how);
		}
		if (set.memoryGib() == null && size.memoryGib() != null && shape.gib() != size.memoryGib()) {
			rounded = rounded.with(Spec.MEMORY_GIB, shape.gib(), how);
		}
		return rounded;
	}

	/**
	 * The cheapest standard shape with at least {@code cpu} vCPU and {@code memoryGib}
	 * GiB: a common vCPU count with 2, 4 or 8 GiB per vCPU.
	 * @param cpu the vCPU needed
	 * @param memoryGib the memory needed
	 * @param set the specs set on the part, which the shape keeps as they are
	 * @return the shape; empty when none fits
	 */
	static Optional<Shape> cheapest(double cpu, double memoryGib, SetSpecs set) {
		List<Double> counts = (set.cpu() != null) ? List.of(set.cpu()) : Sizing.CPU_STEPS;
		return counts.stream()
			.filter((vcpu) -> vcpu >= cpu - TOLERANCE)
			.flatMap((vcpu) -> Sizing.GIB_PER_VCPU.stream().map((ratio) -> new Shape(vcpu, vcpu * ratio)))
			.filter((shape) -> shape.gib() >= memoryGib - TOLERANCE && keeps(shape, set.memoryGib()))
			.min(Comparator.comparingDouble(Shape::price)
				.thenComparingDouble(Shape::vcpu)
				.thenComparingDouble(Shape::gib));
	}

	private static boolean keeps(Shape shape, @Nullable Double setMemory) {
		return setMemory == null || Math.abs(shape.gib() - setMemory) <= TOLERANCE;
	}

	/**
	 * A standard machine shape.
	 *
	 * @param vcpu its vCPU
	 * @param gib its memory, in GiB
	 */
	record Shape(double vcpu, double gib) {

		/**
		 * Its hourly price at generic rates.
		 * @return the price, in USD
		 */
		double price() {
			return this.vcpu * VCPU_HOUR + this.gib * GIB_HOUR;
		}

	}

}
