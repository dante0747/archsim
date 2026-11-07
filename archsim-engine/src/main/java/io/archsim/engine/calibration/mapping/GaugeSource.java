package io.archsim.engine.calibration.mapping;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.calibration.prometheus.Reduction;
import io.archsim.model.codec.Default;
import io.archsim.model.codec.KeyTag;

/**
 * A setting read from a gauge query, reduced to one figure over the range.
 *
 * @param gauge the query
 * @param reduce how the range becomes one figure
 * @param round whether the figure is rounded to a whole number
 * @param unit the gauge's unit, for memory and size settings (converted to theirs);
 * {@code null} for none
 */
@KeyTag("gauge")
public record GaugeSource(String gauge, @Default("mean") Reduction reduce, @Default("false") boolean round,
		@Nullable MemoryUnit unit) implements NodeSource {

}
