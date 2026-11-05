package io.archsim.engine.seeds;

import org.jspecify.annotations.Nullable;

/**
 * The lowest and highest seed of a group of runs; {@code null} for a group without runs.
 *
 * @param first the lowest seed
 * @param last the highest seed
 */
public record SeedSpan(@Nullable Long first, @Nullable Long last) {

}
