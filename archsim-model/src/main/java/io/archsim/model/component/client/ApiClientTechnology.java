package io.archsim.model.component.client;

import io.archsim.model.codec.JsonEnum;

/** What an API consumer uses: a label and logo, behaviour comes from the settings. */
public enum ApiClientTechnology implements JsonEnum {

	/** An API client of no particular kind. */
	API_CLIENT("api_client"),
	/** A REST client. */
	REST("rest"),
	/** A GraphQL client. */
	GRAPHQL("graphql");

	private final String json;

	ApiClientTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
