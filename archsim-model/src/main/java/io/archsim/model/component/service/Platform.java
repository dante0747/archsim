package io.archsim.model.component.service;

import io.archsim.model.codec.JsonEnum;

/** Where a service runs. */
public enum Platform implements JsonEnum {

	/** Virtual machines. */
	VM("vm"),
	/** Docker containers, which behave like virtual machines. */
	DOCKER("docker"),
	/** Pods on a Kubernetes cluster's nodes. */
	KUBERNETES("kubernetes"),
	/** Functions, one request per instance, with cold starts. */
	SERVERLESS("serverless");

	private final String json;

	Platform(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
