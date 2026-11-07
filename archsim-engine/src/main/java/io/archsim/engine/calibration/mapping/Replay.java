package io.archsim.engine.calibration.mapping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;

/**
 * What the calibrated model is checked against: what clients observed end to end, and
 * parts' own latency.
 *
 * @param endToEnd what clients observed; {@code null} for nothing
 * @param components each part's own latency, by part id
 */
public record Replay(@Nullable ReplayTarget endToEnd, @Default("{}") Map<String, ReplayTarget> components) {

	/**
	 * Copies the parts' targets.
	 */
	public Replay {
		components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
	}

}
