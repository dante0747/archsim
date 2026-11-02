package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/**
 * Where an input came from: fitted from telemetry, typed by a person, or a default or
 * preset.
 */
public enum ProvenanceKind implements JsonEnum {

	/** Fitted from telemetry. */
	MEASURED("measured"),
	/** Typed by a person. */
	ASSUMED("assumed"),
	/** An ArchSim default or preset. */
	ILLUSTRATIVE("illustrative");

	private final String json;

	ProvenanceKind(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
