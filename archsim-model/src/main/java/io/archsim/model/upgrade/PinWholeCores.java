package io.archsim.model.upgrade;

import java.util.function.UnaryOperator;

import tools.jackson.databind.node.ObjectNode;

/**
 * The step to schema 1.11, from which a vCPU of a service, database or cache counts as a
 * hyperthread unless its design, its instance family or its platform says otherwise
 * (ADR-0091). Before it every vCPU that set no sibling speed was a whole core, so the
 * step says so once, for the whole design: it sets the architecture's
 * {@code siblingSpeed} to 1, and the design keeps the results it gave. Its parts are left
 * as they are, and so is a sibling speed the architecture already sets. A document
 * without an architecture has no part to pin.
 */
final class PinWholeCores implements UnaryOperator<ObjectNode> {

	/**
	 * The architecture's sibling speed, as schema 1.11 writes it: a step reads and writes
	 * the JSON of its schema, below the records that read the current one.
	 */
	private static final String SIBLING_SPEED = "siblingSpeed";

	/** The sibling speed of vCPUs that are whole cores. */
	private static final int WHOLE_CORES = 1;

	@Override
	public ObjectNode apply(ObjectNode document) {
		if (document.get("architecture") instanceof ObjectNode architecture && !architecture.has(SIBLING_SPEED)) {
			architecture.put(SIBLING_SPEED, WHOLE_CORES);
		}
		return document;
	}

}
