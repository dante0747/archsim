package io.archsim.engine.yaml;

/**
 * A scalar of an explicit tag that PyYAML's own code fails to read with an
 * {@code IndexError} or an {@code AttributeError} rather than a refusal ({@code !!int ''}
 * indexed past its end, {@code !!timestamp soon} matched to nothing). The message is
 * Python's {@code str()} of the error. A key looked up in vain is a
 * {@link YamlKeyException}.
 */
public final class YamlScalarException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * A scalar Python fails to read.
	 * @param message the error's {@code str()}
	 */
	YamlScalarException(String message) {
		super(message);
	}

}
