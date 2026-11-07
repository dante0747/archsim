package io.archsim.engine.calibration.mapping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.archsim.model.codec.KeyTag;

/**
 * A hit ratio from the rates of hits and misses.
 *
 * @param ratio the {@code hits} and {@code misses} queries
 */
@KeyTag("ratio")
public record RatioSource(Map<String, String> ratio) implements NodeSource {

	/**
	 * Checks that it has exactly the two queries.
	 * @throws IllegalArgumentException with the Python engine's words otherwise
	 */
	public RatioSource {
		if (!ratio.keySet().equals(Set.of("hits", "misses"))) {
			throw new IllegalArgumentException("ratio needs exactly 'hits' and 'misses' queries");
		}
		ratio = Collections.unmodifiableMap(new LinkedHashMap<>(ratio));
	}

}
