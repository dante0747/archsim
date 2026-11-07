package io.archsim.engine.calibration.fit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * Measured latencies fitted to a distribution.
 *
 * @param distribution the distribution, as a design writes it: a lognormal by its median
 * and p95, or an empirical quantile table. It is JSON, not a model record, because a
 * table built from a histogram can round two shares near 1 to the same value, which the
 * design's validation then refuses, as it refused the Python engine's
 * @param error the largest relative error against the data; {@code null} when there is
 * nothing independent to check against (an empirical table built from histogram buckets)
 * @param observed the observed quantiles, quantile to milliseconds, in rising order
 * @param count how many observations (or their rate) the fit rests on
 * @param notes what the fit leaves out, in words
 */
public record Fit(ObjectNode distribution, @Nullable Double error, Map<Double, Double> observed, double count,
		List<String> notes) {

	/**
	 * Copies the distribution, the quantiles and the notes.
	 */
	public Fit {
		distribution = distribution.deepCopy();
		observed = Collections.unmodifiableMap(new LinkedHashMap<>(observed));
		notes = List.copyOf(notes);
	}

	/**
	 * The distribution, as a copy: a record does not change.
	 * @return a copy of the distribution
	 */
	@Override
	public ObjectNode distribution() {
		return this.distribution.deepCopy();
	}

}
