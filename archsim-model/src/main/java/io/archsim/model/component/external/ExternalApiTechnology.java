package io.archsim.model.component.external;

import io.archsim.model.codec.JsonEnum;

/** The kind of third-party provider: a label; presets set illustrative numbers. */
public enum ExternalApiTechnology implements JsonEnum {

	PAYMENTS("payments"), EMAIL("email"), SMS("sms"), IDENTITY("identity"), LLM("llm"),
	OBJECT_STORAGE("object_storage"), MAPS("maps"), GENERIC("generic");

	private final String json;

	ExternalApiTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
