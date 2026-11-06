package io.archsim.engine.imports;

import tools.jackson.databind.node.ObjectNode;

/**
 * A design an import built, and its report.
 *
 * @param document the design, as built; a copy goes in and comes out
 * @param report what the import mapped, guessed and ignored
 */
public record ImportedDesign(ObjectNode document, ImportReport report) {

	/**
	 * Copies the design, so the record does not change.
	 */
	public ImportedDesign {
		document = document.deepCopy();
	}

	/**
	 * The design, as a copy: a record does not change.
	 * @return a copy of the design
	 */
	@Override
	public ObjectNode document() {
		return this.document.deepCopy();
	}

}
