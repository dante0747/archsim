package io.archsim.engine.assumptions;

import org.jspecify.annotations.Nullable;

/**
 * An engine-wide number, with where it lives and its basis.
 *
 * @param area the model it belongs to, such as {@code Auto-sizing}
 * @param name what it is
 * @param value its value as the page shows it, with its unit
 * @param code where it lives, in the Python engine's names (such as
 * {@code sizing.TARGET_CPU}), as the page has always named them
 * @param source the documentation that states it; {@code null} when it is illustrative
 */
public record EngineConstant(String area, String name, String value, String code, @Nullable String source) {

}
