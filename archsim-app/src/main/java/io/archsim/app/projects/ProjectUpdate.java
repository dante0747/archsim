package io.archsim.app.projects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.IgnoreUnknownKeys;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;

/**
 * What {@code PUT /projects/{id}} takes: whatever changes.
 *
 * @param name the new name
 * @param description the new description
 * @param document the new design
 * @param expectedRevision the revision the editor saved over; the update is refused when
 * the project has moved on since
 */
@IgnoreUnknownKeys
record ProjectUpdate(@MinLength(1) @MaxLength(120) @Nullable String name, @MaxLength(4000) @Nullable String description,
		@Nullable ObjectNode document, @Nullable Integer expectedRevision) {

}
