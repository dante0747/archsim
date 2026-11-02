package io.archsim.model.distribution;

import static io.archsim.model.distribution.Distribution.MAX_SAMPLE_MS;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Tag;
import io.archsim.model.text.PythonFormat;

/** Uniformly distributed between {@code min} and {@code max}. */
@Tag("uniform")
@Description("Uniformly distributed between ``min`` and ``max``.")
public record Uniform(
        @Description("Minimum in ms") @Ge(0) @Le(MAX_SAMPLE_MS)
        double min,

        @Description("Maximum in ms") @Ge(0) @Le(MAX_SAMPLE_MS)
        double max)
        implements Distribution {

    public Uniform {
        if (max < min) {
            throw new IllegalArgumentException("uniform distribution requires max >= min");
        }
    }

    @Override
    public double expected() {
        return (min + max) / 2.0;
    }

    @Override
    public String describe() {
        return "uniform %s-%s ms".formatted(PythonFormat.format(min, "g"), PythonFormat.format(max, "g"));
    }
}
