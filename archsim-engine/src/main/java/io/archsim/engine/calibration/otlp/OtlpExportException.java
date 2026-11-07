package io.archsim.engine.calibration.otlp;

/**
 * Data that is not an OTLP JSON export: what the Python engine's {@code parse_otlp}
 * failed on with an {@code AttributeError} (something that is not an object where it
 * looks a key up) or a {@code TypeError} (something it cannot iterate, index or turn into
 * a time), as its {@code calibrate_otlp} words it:
 * {@code not an OTLP JSON export (TypeError)}.
 */
public final class OtlpExportException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	/**
	 * Data that is not an export.
	 * @param pythonError the error Python raised, {@code AttributeError} or
	 * {@code TypeError}
	 */
	OtlpExportException(String pythonError) {
		super("not an OTLP JSON export (" + pythonError + ")");
	}

	/**
	 * Something that is not an object where Python looks a key up.
	 * @return the exception
	 */
	static OtlpExportException attributeError() {
		return new OtlpExportException("AttributeError");
	}

	/**
	 * Something Python cannot iterate, index, hash or turn into a time.
	 * @return the exception
	 */
	static OtlpExportException typeError() {
		return new OtlpExportException("TypeError");
	}

}
