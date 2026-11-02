package io.archsim.model.component.client;

import io.archsim.model.codec.JsonEnum;

/** What an API consumer uses: a label and logo, behaviour comes from the settings. */
public enum ApiClientTechnology implements JsonEnum {

	API_CLIENT("api_client"), REST("rest"), GRAPHQL("graphql");

	private final String json;

	ApiClientTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
