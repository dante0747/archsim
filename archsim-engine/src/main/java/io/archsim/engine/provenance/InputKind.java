package io.archsim.engine.provenance;

import io.archsim.model.codec.JsonEnum;
import io.archsim.model.document.ProvenanceKind;

/**
 * Where an input's value came from, in the order reports count them. "Documented" is
 * derived, never stored: a document's provenance records keep the schema's three kinds.
 */
public enum InputKind implements JsonEnum {

	/** Fitted from telemetry, and still at the value it was fitted to. */
	MEASURED("measured"),

	/** The value a technology's documentation states. */
	DOCUMENTED("documented"),

	/** Typed by a person. */
	ASSUMED("assumed"),

	/** ArchSim's default, or another number a technology's profile or preset fills in. */
	ILLUSTRATIVE("illustrative");

	private final String json;

	InputKind(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * The kind a document's provenance record states.
	 * @param kind the record's kind
	 * @return the same kind
	 */
	static InputKind of(ProvenanceKind kind) {
		return switch (kind) {
			case MEASURED -> MEASURED;
			case ASSUMED -> ASSUMED;
			case ILLUSTRATIVE -> ILLUSTRATIVE;
		};
	}

}
