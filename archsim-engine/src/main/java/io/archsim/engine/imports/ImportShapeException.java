package io.archsim.engine.imports;

/**
 * A value of the wrong kind where an importer expects a mapping, a list or a name: the
 * text is not the format. The message is the detail, which the importer refuses the text
 * with ({@code this is not a docker-compose file the import can read (…)}).
 */
final class ImportShapeException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * A value of the wrong kind.
	 * @param detail what was wrong, as Python said it
	 */
	ImportShapeException(String detail) {
		super(detail);
	}

	/**
	 * A value of the wrong kind, as another exception showed it.
	 * @param detail what was wrong, as Python said it
	 * @param cause what showed it
	 */
	ImportShapeException(String detail, Throwable cause) {
		super(detail, cause);
	}

}
