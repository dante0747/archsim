package io.archsim.app.shares;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.IgnoreUnknownKeys;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;

/**
 * What {@code POST /projects/from-share} takes.
 *
 * @param slug the share link's id
 * @param name the new project's name (default: the shared project's, with " (copy)")
 */
@IgnoreUnknownKeys
record CopyFromShare(@MinLength(1) @MaxLength(64) String slug, @MinLength(1) @MaxLength(120) @Nullable String name) {

}
