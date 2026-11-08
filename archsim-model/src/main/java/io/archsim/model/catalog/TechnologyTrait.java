package io.archsim.model.catalog;

import org.jspecify.annotations.Nullable;

/**
 * One behaviour a technology brings, shown to the user with where it comes from, as a
 * run's report lists it for each part.
 *
 * @param id the behaviour's name, such as {@code gc_pauses}
 * @param title its title
 * @param detail what it does, in a sentence or two
 * @param source the documentation behind it; {@code null} when it is illustrative
 */
public record TechnologyTrait(String id, String title, String detail, @Nullable String source) {
}
