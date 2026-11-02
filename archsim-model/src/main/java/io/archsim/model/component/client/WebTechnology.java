package io.archsim.model.component.client;

import io.archsim.model.codec.JsonEnum;

/**
 * What a web client is built with: a label and logo, behaviour comes from the settings.
 */
public enum WebTechnology implements JsonEnum {

	/** A web site of no particular framework. */
	BROWSER("browser"),
	/** A React app. */
	REACT("react"),
	/** A Next.js app. */
	NEXTJS("nextjs"),
	/** A Vue app. */
	VUE("vue"),
	/** An Angular app. */
	ANGULAR("angular"),
	/** A Svelte app. */
	SVELTE("svelte");

	private final String json;

	WebTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
