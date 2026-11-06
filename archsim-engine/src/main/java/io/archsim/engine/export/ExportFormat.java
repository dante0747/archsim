package io.archsim.engine.export;

import io.archsim.model.codec.JsonEnum;

/** What a design is exported as. */
public enum ExportFormat implements JsonEnum {

	/** A docker-compose file. */
	COMPOSE("compose", "docker-compose.yml", "application/yaml"),

	/** Kubernetes manifests. */
	K8S("k8s", "k8s.yaml", "application/yaml"),

	/** A Mermaid diagram. */
	MERMAID("mermaid", "mmd", "text/plain");

	private final String json;

	private final String suffix;

	private final String mediaType;

	ExportFormat(String json, String suffix, String mediaType) {
		this.json = json;
		this.suffix = suffix;
		this.mediaType = mediaType;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * The file name suffix suggested for the text.
	 * @return the suffix
	 */
	public String suffix() {
		return this.suffix;
	}

	/**
	 * The media type of the text.
	 * @return the media type
	 */
	public String mediaType() {
		return this.mediaType;
	}

}
