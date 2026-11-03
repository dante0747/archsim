package io.archsim.app.access;

import java.util.Locale;

/**
 * How a request says who it is.
 */
public enum PrincipalKind {

	/** No sign-in: the local user. */
	LOCAL,

	/** A browser session's cookie. */
	SESSION,

	/** A personal API token. */
	TOKEN;

	/**
	 * How the API names this kind: {@code local}, {@code session} or {@code token}.
	 * @return the name in JSON
	 */
	public String json() {
		return name().toLowerCase(Locale.ROOT);
	}

}
