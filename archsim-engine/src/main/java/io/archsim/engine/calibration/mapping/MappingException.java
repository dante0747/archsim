package io.archsim.engine.calibration.mapping;

/**
 * A calibration mapping that cannot be used. The message names what is wrong and where,
 * never the values it was given: a mapping can come from a request, and errors go back to
 * whoever sent it. For the same reason it has no cause, whose message could quote the
 * text.
 */
public final class MappingException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * A mapping that cannot be used.
	 * @param message what is wrong, in the Python engine's words
	 */
	MappingException(String message) {
		super(message);
	}

}
