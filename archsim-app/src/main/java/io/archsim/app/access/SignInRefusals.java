package io.archsim.app.access;

/**
 * What a request that cannot be signed in is told, in one place: the token filter's entry
 * point, the token introspector and the principal's resolver say the same.
 */
final class SignInRefusals {

	/** No session cookie and no API token, where the endpoint needs someone. */
	static final String SIGN_IN = "Sign in to continue";

	/** A header that carries no API token of ours. */
	static final String SEND_A_TOKEN = "Send an API token as Authorization: Bearer " + Secrets.TOKEN_PREFIX + "...";

	/** An API token that is unknown, revoked or expired. */
	static final String TOKEN_NOT_VALID = "This API token is not valid: it was revoked, expired or mistyped";

	/** A session that is unknown or expired. */
	static final String SESSION_EXPIRED = "Your sign-in has expired: sign in again";

	/** A token or session whose account is disabled. */
	static final String ACCOUNT_DISABLED = "This account cannot sign in";

	private SignInRefusals() {
	}

}
