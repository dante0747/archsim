package io.archsim.model.distribution;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Tag;
import io.archsim.model.text.PythonFormat;

/**
 * Uniformly distributed between {@code min} and {@code max}.
 *
 * @param min the lowest value, in milliseconds
 * @param max the highest value, in milliseconds, at least {@code min}
 */
@Tag("uniform")
@Description("Uniformly distributed between ``min`` and ``max``.")
public record Uniform(@Description("Minimum in ms") @Ge(0) @Le(MAX_SAMPLE_MS) double min,

		@Description("Maximum in ms") @Ge(0) @Le(MAX_SAMPLE_MS) double max) implements Distribution {

	/**
	 * Checks the maximum is at least the minimum; the comparison is written so that NaN
	 * fails it.
	 */
	public Uniform {
		if (!(max >= min)) {
			throw new IllegalArgumentException("uniform distribution requires max >= min");
		}
	}

	@Override
	public double expected() {
		return (this.min + this.max) / 2.0;
	}

	@Override
	public String describe() {
		return "uniform %s-%s ms".formatted(PythonFormat.format(this.min, "g"), PythonFormat.format(this.max, "g"));
	}
}
