package io.archsim.model.component.client;

import io.archsim.model.codec.JsonEnum;

/**
 * What a mobile client is built with: a label and logo, behaviour comes from the
 * settings.
 */
public enum MobileTechnology implements JsonEnum {

	MOBILE("mobile"), IOS("ios"), ANDROID("android"), REACT_NATIVE("react_native"), FLUTTER("flutter");

	private final String json;

	MobileTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
