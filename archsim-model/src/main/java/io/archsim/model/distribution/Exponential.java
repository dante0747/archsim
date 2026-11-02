package io.archsim.model.distribution;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Tag;
import io.archsim.model.text.PythonFormat;

/**
 * Memoryless (M in queueing notation), with a high variance: its standard deviation is
 * its mean.
 *
 * @param mean the mean, in milliseconds
 */
@Tag("exponential")
@Description("Memoryless distribution (M in queueing notation). High variance: stddev == mean.")
public record Exponential(@Description("Mean in ms") @Gt(0) @Le(MAX_SAMPLE_MS) double mean) implements Distribution {

	@Override
	public double expected() {
		return this.mean;
	}

	@Override
	public String describe() {
		return "exponential, mean %s ms".formatted(PythonFormat.format(this.mean, "g"));
	}

}
