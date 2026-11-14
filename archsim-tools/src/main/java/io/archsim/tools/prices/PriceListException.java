package io.archsim.tools.prices;

import java.io.Serial;

/**
 * A price list that does not read as the refresh expects, so that its format may have
 * changed: a type at two prices, a rate at several, a list without its header row. The
 * refresh stops rather than write a snapshot that could hold the wrong price.
 */
public final class PriceListException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * A price list that does not read as expected.
	 * @param message what was found, in a sentence
	 */
	public PriceListException(String message) {
		super(message);
	}

}
