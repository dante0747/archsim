package io.archsim.engine.calibration.mapping;

import io.archsim.engine.calibration.fit.FitKind;
import io.archsim.model.codec.Default;
import io.archsim.model.codec.KeyTag;

/**
 * A latency setting fitted from a histogram query.
 *
 * @param histogram the query, such as {@code sum by (le) (rate(..._bucket[5m]))}
 * @param unit the unit of its bucket bounds
 * @param fit a lognormal or an empirical table
 */
@KeyTag("histogram")
public record HistogramSource(String histogram, @Default("s") LatencyUnit unit,
		@Default("lognormal") FitKind fit) implements NodeSource {

}
