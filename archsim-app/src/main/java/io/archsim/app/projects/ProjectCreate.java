package io.archsim.app.projects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.IgnoreUnknownKeys;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;

/**
 * What {@code POST /projects} takes: a design, an example to copy, or neither for a blank
 * design.
 *
 * @param name the project's name; the design's own when not given
 * @param description what it is for
 * @param document the design
 * @param exampleId the example to copy instead
 * @param reuseUnedited with an example and no name: hand back the asker's copy of it that
 * was never saved, instead of making another
 */
@IgnoreUnknownKeys
record ProjectCreate(@MinLength(1) @MaxLength(120) @Nullable String name, @MaxLength(4000) @Nullable String description,
		@Nullable ObjectNode document, @MaxLength(100) @Nullable String exampleId,
		@Default("false") boolean reuseUnedited) {

}
