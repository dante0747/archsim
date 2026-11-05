package io.archsim.engine.confidence;

import java.util.Optional;

import io.archsim.engine.provenance.Input;
import io.archsim.engine.provenance.InputScope;

/**
 * A part or a link a result's weight is spread over.
 *
 * @param scope a part's or a link's
 * @param id the part's or link's id
 */
public record PartKey(InputScope scope, String id) {

	/**
	 * A part.
	 * @param id its id
	 * @return its key
	 */
	public static PartKey node(String id) {
		return new PartKey(InputScope.NODE, id);
	}

	/**
	 * A link.
	 * @param id its id
	 * @return its key
	 */
	public static PartKey edge(String id) {
		return new PartKey(InputScope.EDGE, id);
	}

	/**
	 * The part or link an input belongs to.
	 * @param input the input
	 * @return its key; empty for the workload's inputs, or an input without an id
	 */
	static Optional<PartKey> of(Input input) {
		String id = input.id();
		if (input.scope() == InputScope.WORKLOAD || id == null || id.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new PartKey(input.scope(), id));
	}

}
