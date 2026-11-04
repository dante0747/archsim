package io.archsim.engine.sizing;

import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.settings.LenientSettings;

/**
 * The CPU and memory set on a part: a spec set on the part always wins over its auto
 * size, and counts as the need when it is snapped to a machine.
 *
 * @param cpu the vCPU set, or {@code null} when it is auto
 * @param memoryGib the memory set, or {@code null} when it is auto
 */
record SetSpecs(@Nullable Double cpu, @Nullable Double memoryGib) {

	/**
	 * The specs a part's settings set.
	 * @param settings the part's settings
	 * @return the specs they set
	 */
	static SetSpecs of(LenientSettings settings) {
		return new SetSpecs(number(settings, Spec.CPU_CORES), number(settings, Spec.MEMORY_GIB));
	}

	/**
	 * Whether the part sets both its CPU and its memory, so nothing is left to round.
	 * @return {@code true} when both are set
	 */
	boolean both() {
		return this.cpu != null && this.memoryGib != null;
	}

	/**
	 * The CPU a size needs: the CPU set, else the auto size.
	 * @param size the auto size
	 * @return the vCPU, or {@code null} when it has none
	 */
	@Nullable Double cpuOr(PartSize size) {
		return (this.cpu != null) ? this.cpu : size.cpuCores();
	}

	/**
	 * The memory a size needs: the memory set, else the auto size.
	 * @param size the auto size
	 * @return the GiB, or {@code null} when it has none
	 */
	@Nullable Double memoryOr(PartSize size) {
		return (this.memoryGib != null) ? this.memoryGib : size.memoryGib();
	}

	private static @Nullable Double number(LenientSettings settings, Spec spec) {
		OptionalDouble value = settings.number(spec.json());
		return value.isPresent() ? value.getAsDouble() : null;
	}

}
