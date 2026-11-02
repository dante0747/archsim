package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/** The side of a component a link attaches to on the canvas. */
public enum EdgeSide implements JsonEnum {

	/** The component's top side. */
	TOP("top"),
	/** The component's right side. */
	RIGHT("right"),
	/** The component's bottom side. */
	BOTTOM("bottom"),
	/** The component's left side. */
	LEFT("left");

	private final String json;

	EdgeSide(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
