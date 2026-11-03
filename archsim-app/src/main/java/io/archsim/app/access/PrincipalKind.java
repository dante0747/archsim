package io.archsim.app.access;

/**
 * How a request says who it is.
 */
public enum PrincipalKind {

	/** No sign-in: the local user. */
	LOCAL,

	/** A browser session's cookie. */
	SESSION,

	/** A personal API token. */
	TOKEN

}
