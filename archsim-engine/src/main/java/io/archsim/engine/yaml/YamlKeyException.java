package io.archsim.engine.yaml;

/**
 * A scalar of an explicit tag that PyYAML's own code looks up in vain, with a
 * {@code KeyError} ({@code !!bool maybe}). The message is Python's {@code str()} of the
 * error: the key, as Python's {@code repr()} writes it.
 */
public final class YamlKeyException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * A key looked up in vain.
	 * @param key the key, as Python's {@code repr()} writes it
	 */
	YamlKeyException(String key) {
		super(key);
	}

}
