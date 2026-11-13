package io.archsim.benchmarks.inputs;

/**
 * A calibration lacks a pass or a timing that today's derivation of the inputs needs, as
 * early protocols did: a development check skips such a run.
 */
public final class MissingReadingException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	/**
	 * What the calibration lacks.
	 * @param message the reading it lacks, in words
	 */
	MissingReadingException(String message) {
		super(message);
	}

}
