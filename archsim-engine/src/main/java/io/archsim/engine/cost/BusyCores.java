package io.archsim.engine.cost;

import java.util.Map;

/**
 * The CPU cores one instance of each auto-sized part keeps busy at the workload's base
 * load and at its peak, so a burstable type above its baseline is charged its surplus CPU
 * credits at each.
 *
 * @param base the busy cores at the base load, by part id
 * @param peak the busy cores at the peak, by part id
 */
public record BusyCores(Map<String, Double> base, Map<String, Double> peak) {

	/**
	 * Copies the maps, so the record does not change.
	 * @param base the busy cores at the base load, by part id
	 * @param peak the busy cores at the peak, by part id
	 */
	public BusyCores {
		base = Map.copyOf(base);
		peak = Map.copyOf(peak);
	}

	/**
	 * No busy cores known: no part is charged surplus credits.
	 * @return the busy cores
	 */
	public static BusyCores none() {
		return new BusyCores(Map.of(), Map.of());
	}

	/**
	 * The busy cores at a level.
	 * @param level the level
	 * @return the busy cores, by part id
	 */
	Map<String, Double> at(LoadLevel level) {
		return (level == LoadLevel.BASE) ? this.base : this.peak;
	}

}
