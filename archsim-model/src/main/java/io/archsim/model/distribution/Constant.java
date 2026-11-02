package io.archsim.model.distribution;

import static io.archsim.model.distribution.Distribution.MAX_SAMPLE_MS;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Tag;
import io.archsim.model.text.PythonFormat;

/** Always the same value. Useful for fixed overheads and deterministic tests. */
@Tag("constant")
@Description("Always the same value. Useful for fixed overheads and deterministic tests.")
public record Constant(
        @Description("Value in ms") @Ge(0) @Le(MAX_SAMPLE_MS)
        double value) implements Distribution {

    @Override
    public double expected() {
        return value;
    }

    @Override
    public String describe() {
        return "constant %s ms".formatted(PythonFormat.format(value, "g"));
    }
}
