package io.archsim.app.signin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.security.core.AuthenticationException;

/**
 * A sign-in that failed. Its code goes to the sign-in page, {@code /signin?error=<code>}.
 * Thrown while Spring Security's OAuth2 login reads the account, it reaches the login's
 * failure handler, {@link Callbacks}, as every failed sign-in does.
 */
final class SignInFailure extends AuthenticationException {

	private static final long serialVersionUID = 1L;

	private static final String PROVIDER = "provider";

	private final String code;

	private SignInFailure(String code, String detail) {
		super(detail);
		this.code = code;
	}

	/**
	 * The state cookie is missing, expired or different, or the callback lacks its code.
	 * @return the failure
	 */
	static SignInFailure state() {
		return new SignInFailure("state", "the sign-in state cookie is missing, expired or different");
	}

	/**
	 * The person refused at the provider.
	 * @return the failure
	 */
	static SignInFailure denied() {
		return new SignInFailure("denied", "the provider refused");
	}

	/**
	 * The provider could not be reached, refused the code, or answered something else.
	 * @param detail what happened, for the log
	 * @return the failure
	 */
	static SignInFailure provider(String detail) {
		return new SignInFailure(PROVIDER, detail);
	}

	/**
	 * The account was disabled.
	 * @return the failure
	 */
	static SignInFailure disabled() {
		return new SignInFailure("disabled", "the account is disabled");
	}

	/**
	 * What the sign-in page is told.
	 * @return {@code state}, {@code denied}, {@code provider} or {@code disabled}
	 */
	String code() {
		return this.code;
	}

	/**
	 * The page a browser lands on when sign-in failed.
	 * @return {@code /signin?error=<code>}
	 */
	String page() {
		return "/signin?error=" + URLEncoder.encode(this.code, StandardCharsets.UTF_8);
	}

	/**
	 * Whether the provider failed, which is worth a look in the log.
	 * @return {@code true} for the {@code provider} code
	 */
	boolean atTheProvider() {
		return PROVIDER.equals(this.code);
	}

}
