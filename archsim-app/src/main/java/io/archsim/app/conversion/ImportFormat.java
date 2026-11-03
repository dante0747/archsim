package io.archsim.app.conversion;

import io.archsim.model.codec.JsonEnum;

/**
 * What a design is imported from.
 */
enum ImportFormat implements JsonEnum {

	/** A docker-compose file. */
	COMPOSE("compose"),

	/** Kubernetes manifests. */
	K8S("k8s"),

	/** A Terraform plan, as {@code terraform show -json} writes it. */
	TERRAFORM("terraform");

	private final String json;

	ImportFormat(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
