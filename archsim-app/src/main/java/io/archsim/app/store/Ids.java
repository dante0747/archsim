package io.archsim.app.store;

import java.util.UUID;

/**
 * Ids of the rows the API makes.
 */
public final class Ids {

	private Ids() {
	}

	/**
	 * A new id, as the Python API makes them.
	 * @return a random UUID's 32 hex digits
	 */
	public static String newId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

}
