package io.archsim.engine.calibration;

import java.util.List;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a calibration gives: the calibrated design (the input is not changed), each input
 * it set, and what it noticed.
 *
 * @param document the calibrated design, with its provenance and calibration record
 * @param changes each input set, in the order set
 * @param notes what it noticed, in words
 */
public record CalibrationOutcome(ObjectNode document, List<Change> changes, List<String> notes) {

	/**
	 * Copies the design, the changes and the notes.
	 */
	public CalibrationOutcome {
		document = document.deepCopy();
		changes = List.copyOf(changes);
		notes = List.copyOf(notes);
	}

	@Override
	public ObjectNode document() {
		return this.document.deepCopy();
	}

	/**
	 * The outcome as the Python engine's {@code Outcome.to_dict} writes it.
	 * @return {@code {document, changes, notes, calibration}}
	 */
	public ObjectNode toJson() {
		ObjectNode json = this.document.objectNode();
		json.set("document", document());
		ArrayNode changesJson = json.putArray("changes");
		this.changes.forEach((change) -> changesJson.add(change.toJson()));
		ArrayNode notesJson = json.putArray("notes");
		this.notes.forEach(notesJson::add);
		json.set("calibration", this.document.has("calibration") ? this.document.get("calibration").deepCopy()
				: this.document.nullNode());
		return json;
	}

}
