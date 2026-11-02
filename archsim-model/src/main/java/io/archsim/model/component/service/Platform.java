package io.archsim.model.component.service;

import io.archsim.model.codec.JsonEnum;

/** Where a service runs. */
public enum Platform implements JsonEnum {

	VM("vm"), DOCKER("docker"), KUBERNETES("kubernetes"), SERVERLESS("serverless");

	private final String json;

	Platform(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
