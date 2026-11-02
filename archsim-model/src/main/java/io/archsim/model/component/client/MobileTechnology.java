package io.archsim.model.component.client;

import io.archsim.model.codec.JsonEnum;

/**
 * What a mobile client is built with: a label and logo, behaviour comes from the
 * settings.
 */
public enum MobileTechnology implements JsonEnum {

	/** A mobile app of no particular platform. */
	MOBILE("mobile"),
	/** A native iOS app. */
	IOS("ios"),
	/** A native Android app. */
	ANDROID("android"),
	/** A React Native app. */
	REACT_NATIVE("react_native"),
	/** A Flutter app. */
	FLUTTER("flutter");

	private final String json;

	MobileTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
