package io.archsim.model.pricing;

import java.io.Serial;

/**
 * A price snapshot that is not there, or not a valid one; the message says which and why.
 */
public final class PricingException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	public PricingException(String message) {
		super(message);
	}

}
