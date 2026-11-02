package io.archsim.model.distribution;

import static io.archsim.model.distribution.Distribution.MAX_SAMPLE_MS;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Tag;
import io.archsim.model.text.PythonFormat;

/** Memoryless (M in queueing notation), with a high variance: its standard deviation is its mean. */
@Tag("exponential")
@Description("Memoryless distribution (M in queueing notation). High variance: stddev == mean.")
public record Exponential(
        @Description("Mean in ms") @Gt(0) @Le(MAX_SAMPLE_MS) double mean) implements Distribution {

    @Override
    public double expected() {
        return mean;
    }

    @Override
    public String describe() {
        return "exponential, mean %s ms".formatted(PythonFormat.format(mean, "g"));
    }
}
