package io.archsim.engine.imports;

/**
 * Text an importer cannot read: not YAML, not the format, or beyond the bounds YAML from
 * anyone is read within. The message says what was expected, in the Python engine's
 * words.
 */
public final class ImportRefusedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * A refusal.
	 * @param message what is wrong, in the Python engine's words
	 */
	public ImportRefusedException(String message) {
		super(message);
	}

	/**
	 * A refusal with the exception that showed what is wrong.
	 * @param message what is wrong, in the Python engine's words
	 * @param cause what showed it
	 */
	public ImportRefusedException(String message, Throwable cause) {
		super(message, cause);
	}

}
