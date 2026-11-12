package io.archsim.app.reviews;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.IgnoreUnknownKeys;
import io.archsim.model.codec.MaxLength;

/**
 * What {@code POST /runs/{id}/signoffs} takes.
 *
 * @param decision approve, or ask for changes
 * @param note why
 */
@IgnoreUnknownKeys
record SignoffCreate(Decision decision, @MaxLength(2000) @Nullable String note) {

}
