package io.archsim.engine.imports;

import java.util.Arrays;
import java.util.stream.Collectors;

import io.archsim.model.codec.JsonEnum;

/** What a design is imported from. */
public enum ImportFormat implements JsonEnum {

	/** A docker-compose file. */
	COMPOSE("compose"),

	/** Kubernetes manifests. */
	K8S("k8s"),

	/** A Terraform plan or state, as {@code terraform show -json} writes it. */
	TERRAFORM("terraform");

	/** The formats' names, in the words of a refusal. */
	private static final String NAMES = Arrays.stream(values())
		.map(ImportFormat::json)
		.collect(Collectors.joining(", "));

	private final String json;

	ImportFormat(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * The format a name names.
	 * @param name the name, such as {@code k8s}
	 * @return the format
	 * @throws ImportRefusedException in the Python engine's words for a name of no format
	 */
	public static ImportFormat named(String name) {
		return Arrays.stream(values())
			.filter((format) -> format.json.equals(name))
			.findFirst()
			.orElseThrow(() -> new ImportRefusedException("unknown import format '" + name + "' (" + NAMES + ")"));
	}

}
