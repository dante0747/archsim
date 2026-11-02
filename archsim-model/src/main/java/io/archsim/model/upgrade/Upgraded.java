package io.archsim.model.upgrade;

import java.util.List;
import tools.jackson.databind.node.ObjectNode;

/**
 * A document upgraded to the current schema, and a note for each step it took
 * ({@code migrated schema 1.5 -> 1.6}).
 *
 * @param document the upgraded document; a copy, the input is not changed
 * @param notes the steps, in order; empty when it was current already
 */
public record Upgraded(ObjectNode document, List<String> notes) {

	public Upgraded {
		notes = List.copyOf(notes);
	}
}
