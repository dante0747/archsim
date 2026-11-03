package io.archsim.app.signin;

/**
 * A sign-in that failed. Its code goes to the sign-in page, {@code /signin?error=<code>}.
 */
final class SignInFailure extends RuntimeException {

	private static final long serialVersionUID = 1L;

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
		return new SignInFailure("provider", detail);
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

}
