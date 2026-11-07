package io.archsim.engine.calibration.prometheus;

import java.util.Map;

/**
 * A float as a key of a Python {@code dict}, which holds {@code 0.0} and {@code -0.0} as
 * one key, the one added first, where a Java map holds two.
 */
final class DictKeys {

	private DictKeys() {
	}

	/**
	 * The key a float is added under.
	 * @param map the map, keyed as a Python dict is
	 * @param key the float
	 * @return the zero of the other sign when the map already has it, else the float
	 */
	static double of(Map<Double, ?> map, double key) {
		return (key == 0.0 && map.containsKey(-key)) ? -key : key;
	}

}
