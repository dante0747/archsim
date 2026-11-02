package io.archsim.model.component.database;

import io.archsim.model.codec.JsonEnum;

/** What a write waits for from replicas; auto follows the technology. */
public enum Replication implements JsonEnum {

	AUTO("auto"), ASYNC("async"), MAJORITY("majority"), ALL("all"), NONE("none");

	private final String json;

	Replication(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
