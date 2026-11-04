package io.archsim.engine.sizing;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The CPU cores one instance of each auto-sized part keeps busy at the steady base load.
 */
public final class BaseBusy {

	private BaseBusy() {
	}

	/**
	 * The busy cores at the base load of the parts that know them, for monthly CPU
	 * credits.
	 * @param sizes the auto sizes, by part id
	 * @return the busy cores, by part id
	 */
	public static Map<String, Double> of(Map<String, PartSize> sizes) {
		Map<String, Double> busy = new LinkedHashMap<>();
		sizes.forEach((id, size) -> {
			Double base = size.need().get(Need.BASE_BUSY_CORES);
			if (base != null) {
				busy.put(id, base);
			}
		});
		return busy;
	}

}
