package io.archsim.model.upgrade;

import java.util.Objects;
import java.util.function.UnaryOperator;

import tools.jackson.databind.node.ObjectNode;

/**
 * One step of the upgrade chain: it turns a document of schema {@code from} into one of
 * schema {@code to}. It works on the JSON, before any record is made, because a record
 * knows only the current schema.
 *
 * @param from the schema version it reads
 * @param to the schema version it writes
 * @param change what the newer schema adds, and what the step does about it
 * @param apply the step itself; it may change the document it is given and returns the
 * result
 */
public record Upgrade(String from, String to, String change, UnaryOperator<ObjectNode> apply) {

	public Upgrade {
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		Objects.requireNonNull(change, "change");
		Objects.requireNonNull(apply, "apply");
	}

	/**
	 * A step for a schema that only adds optional fields: the older document reads
	 * unchanged.
	 */
	static Upgrade additive(String from, String to, String change) {
		return new Upgrade(from, to, change, UnaryOperator.identity());
	}
}
