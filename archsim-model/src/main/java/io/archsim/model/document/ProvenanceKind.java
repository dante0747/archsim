package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/**
 * Where an input came from: fitted from telemetry, typed by a person, or a default or
 * preset.
 */
public enum ProvenanceKind implements JsonEnum {

	MEASURED("measured"), ASSUMED("assumed"), ILLUSTRATIVE("illustrative");

	private final String json;

	ProvenanceKind(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
