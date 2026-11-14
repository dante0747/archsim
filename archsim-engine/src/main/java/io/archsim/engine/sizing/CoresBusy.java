package io.archsim.engine.sizing;

import java.util.LinkedHashMap;
import java.util.Map;

import io.archsim.engine.cost.BusyCores;

/**
 * The CPU cores one instance of each auto-sized part keeps busy at the workload's steady
 * base load and at its peak, for the CPU credits of an hour at each.
 */
public final class CoresBusy {

	private CoresBusy() {
	}

	/**
	 * The busy cores at the base load and at the peak of the parts that know them.
	 * @param sizes the auto sizes, by part id
	 * @return the busy cores at each load
	 */
	public static BusyCores of(Map<String, PartSize> sizes) {
		return new BusyCores(at(sizes, Need.BASE_BUSY_CORES), at(sizes, Need.BUSY_CORES));
	}

	/**
	 * The busy cores one need gives of the parts that know them.
	 * @param sizes the auto sizes, by part id
	 * @param need the busy cores at the base load, or at the peak
	 * @return the busy cores, by part id
	 */
	private static Map<String, Double> at(Map<String, PartSize> sizes, Need need) {
		Map<String, Double> busy = new LinkedHashMap<>();
		sizes.forEach((id, size) -> {
			Double cores = size.need().get(need);
			if (cores != null) {
				busy.put(id, cores);
			}
		});
		return busy;
	}

}
