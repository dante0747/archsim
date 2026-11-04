package io.archsim.engine.sizing;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.pricing.Busy;

/**
 * The auto size of one part: the CPU, memory and storage it gets for the design's own
 * peak, how each was worked out, and what it needs before rounding to an instance.
 *
 * @param cpuCores the vCPU of each instance, or {@code null} when it has no such spec or
 * is not sized
 * @param memoryGib the memory of each instance, in GiB, or {@code null}
 * @param storageGib the disk of each instance, in GiB, or {@code null}
 * @param peakRps the requests per second it carries at the peak
 * @param why how each spec was worked out
 * @param skipped why the part is not sized, or {@code null} when it is
 * @param sku the instance type the size snapped to, with a price snapshot
 * @param need what it needs, before rounding to an instance
 * @param memoryModel a service's memory model, for its typical memory
 * @param smallest its CPU need is below the smallest size it can get
 */
public record PartSize(@Nullable Double cpuCores, @Nullable Double memoryGib, @Nullable Double storageGib,
		double peakRps, Map<Spec, String> why, @Nullable String skipped, @Nullable String sku, Map<Need, Double> need,
		@Nullable MemoryModel memoryModel, boolean smallest) {

	/**
	 * Copies the explanations and needs, so the record does not change.
	 */
	public PartSize {
		why = Collections.unmodifiableMap(copy(Spec.class, why));
		need = Collections.unmodifiableMap(copy(Need.class, need));
	}

	/**
	 * A part that is not sized.
	 * @param peakRps the requests per second it carries at the peak
	 * @param reason why it is not sized
	 * @return its size: no specs
	 */
	static PartSize skipped(double peakRps, String reason) {
		return new PartSize(null, null, null, peakRps, Map.of(), reason, null, Map.of(), null, false);
	}

	/**
	 * One of its specs.
	 * @param spec the spec
	 * @return its value; empty when it has none
	 */
	public Optional<Double> spec(Spec spec) {
		return Optional.ofNullable(switch (spec) {
			case CPU_CORES -> this.cpuCores;
			case MEMORY_GIB -> this.memoryGib;
			case STORAGE_GIB -> this.storageGib;
		});
	}

	/**
	 * The CPU cores one instance keeps busy at the peak and at the base load.
	 * @return them; empty when either is unknown
	 */
	public Optional<Busy> busy() {
		Double peak = this.need.get(Need.BUSY_CORES);
		Double base = this.need.get(Need.BASE_BUSY_CORES);
		return (peak != null && base != null) ? Optional.of(new Busy(peak, base)) : Optional.empty();
	}

	/**
	 * This size with one spec changed, and its explanation extended.
	 * @param spec the spec
	 * @param value its new value
	 * @param because what the explanation goes on with
	 * @return the changed size
	 */
	PartSize with(Spec spec, double value, String because) {
		return explained(spec, because).withValue(spec, value);
	}

	/**
	 * This size with one spec's explanation extended.
	 * @param spec the spec
	 * @param because what the explanation goes on with
	 * @return the changed size
	 */
	PartSize explained(Spec spec, String because) {
		Map<Spec, String> explained = copy(Spec.class, this.why);
		explained.merge(spec, because, String::concat);
		return new PartSize(this.cpuCores, this.memoryGib, this.storageGib, this.peakRps, explained, this.skipped,
				this.sku, this.need, this.memoryModel, this.smallest);
	}

	/**
	 * This size with one more need.
	 * @param key what is needed
	 * @param value how much
	 * @return the changed size
	 */
	PartSize withNeed(Need key, double value) {
		Map<Need, Double> needs = copy(Need.class, this.need);
		needs.put(key, value);
		return new PartSize(this.cpuCores, this.memoryGib, this.storageGib, this.peakRps, this.why, this.skipped,
				this.sku, needs, this.memoryModel, this.smallest);
	}

	/**
	 * This size on an instance type.
	 * @param type the instance type's name
	 * @return the changed size
	 */
	PartSize onInstanceType(String type) {
		return new PartSize(this.cpuCores, this.memoryGib, this.storageGib, this.peakRps, this.why, this.skipped, type,
				this.need, this.memoryModel, this.smallest);
	}

	/**
	 * This size with its {@code smallest} mark set.
	 * @param smallest whether its CPU need is below the smallest size it can get
	 * @return the changed size
	 */
	PartSize withSmallest(boolean smallest) {
		return new PartSize(this.cpuCores, this.memoryGib, this.storageGib, this.peakRps, this.why, this.skipped,
				this.sku, this.need, this.memoryModel, smallest);
	}

	/**
	 * This size with only the specs a part has, and the load it was sized for.
	 * @param specs the specs the part's type has
	 * @param rps the requests per second it carries at the peak
	 * @return the size the part gets
	 */
	PartSize forSpecs(Set<Spec> specs, double rps) {
		Map<Spec, String> explained = copy(Spec.class, this.why);
		explained.keySet().retainAll(specs);
		Map<Need, Double> needs = copy(Need.class, this.need);
		needs.keySet().removeIf((key) -> !specs.contains(key.spec()));
		return new PartSize(only(specs, Spec.CPU_CORES, this.cpuCores), only(specs, Spec.MEMORY_GIB, this.memoryGib),
				only(specs, Spec.STORAGE_GIB, this.storageGib), rps, explained, null, this.sku, needs,
				specs.contains(Spec.MEMORY_GIB) ? this.memoryModel : null, this.smallest);
	}

	private PartSize withValue(Spec spec, double value) {
		Double changed = value;
		return new PartSize((spec == Spec.CPU_CORES) ? changed : this.cpuCores,
				(spec == Spec.MEMORY_GIB) ? changed : this.memoryGib,
				(spec == Spec.STORAGE_GIB) ? changed : this.storageGib, this.peakRps, this.why, this.skipped, this.sku,
				this.need, this.memoryModel, this.smallest);
	}

	private static @Nullable Double only(Set<Spec> specs, Spec spec, @Nullable Double value) {
		return specs.contains(spec) ? value : null;
	}

	private static <K extends Enum<K>, V> Map<K, V> copy(Class<K> type, Map<K, V> map) {
		Map<K, V> copy = new EnumMap<>(type);
		copy.putAll(map);
		return copy;
	}

}
