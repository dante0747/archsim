package io.archsim.model.component.external;

import io.archsim.model.codec.JsonEnum;

/** The kind of third-party provider: a label; presets set illustrative numbers. */
public enum ExternalApiTechnology implements JsonEnum {

	/** A payment provider. */
	PAYMENTS("payments"),
	/** An email delivery service. */
	EMAIL("email"),
	/** A text message gateway. */
	SMS("sms"),
	/** An identity provider. */
	IDENTITY("identity"),
	/** A large language model's inference API. */
	LLM("llm"),
	/** An object store. */
	OBJECT_STORAGE("object_storage"),
	/** A maps and geocoding service. */
	MAPS("maps"),
	/** A third-party service of no particular kind. */
	GENERIC("generic");

	private final String json;

	ExternalApiTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
