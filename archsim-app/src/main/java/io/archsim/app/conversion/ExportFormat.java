package io.archsim.app.conversion;

import io.archsim.model.codec.JsonEnum;

/**
 * What a design is exported as.
 */
enum ExportFormat implements JsonEnum {

	/** A docker-compose file. */
	COMPOSE("compose"),

	/** Kubernetes manifests. */
	K8S("k8s"),

	/** A Mermaid diagram. */
	MERMAID("mermaid");

	private final String json;

	ExportFormat(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
