package io.archsim.model.component.database;

import io.archsim.model.codec.JsonEnum;

/** What a write waits for from replicas; auto follows the technology. */
public enum Replication implements JsonEnum {

	/** Whatever the technology does by default. */
	AUTO("auto"),
	/** The primary commits and the replicas replay the write later, so they lag. */
	ASYNC("async"),
	/** A write also waits until a majority of the copies have it. */
	MAJORITY("majority"),
	/** A write also waits until every replica has it. */
	ALL("all"),
	/** Writes are not replayed on replicas, which only serve reads. */
	NONE("none");

	private final String json;

	Replication(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
