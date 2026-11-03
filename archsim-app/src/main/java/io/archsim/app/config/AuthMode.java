package io.archsim.app.config;

/**
 * How people reach the API ({@code ARCHSIM_AUTH_MODE}).
 */
public enum AuthMode {

	/** No sign-in: every request is the local owner of the default workspace. */
	NONE,

	/** Sign-in with GitHub or Google, sessions and API tokens. */
	OAUTH

}
