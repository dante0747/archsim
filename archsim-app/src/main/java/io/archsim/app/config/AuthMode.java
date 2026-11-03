package io.archsim.app.config;

import java.util.Locale;

/**
 * How people reach the API ({@code ARCHSIM_AUTH_MODE}).
 */
public enum AuthMode {

	/** No sign-in: every request is the local owner of the default workspace. */
	NONE,

	/** Sign-in with GitHub or Google, sessions and API tokens. */
	OAUTH;

	/**
	 * How the API names this mode: {@code none} or {@code oauth}.
	 * @return the name in JSON
	 */
	public String json() {
		return name().toLowerCase(Locale.ROOT);
	}

}
