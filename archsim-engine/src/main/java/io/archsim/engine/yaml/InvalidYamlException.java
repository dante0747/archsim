package io.archsim.engine.yaml;

/**
 * Text that is not valid YAML: what PyYAML raised as a {@code YAMLError}, whether its
 * parser could not read the text, its safe constructor refused a value, or one document
 * was expected and the text held several. The message is the problem in the parser's or
 * the constructor's words.
 */
public final class InvalidYamlException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Text that is not valid YAML.
	 * @param message the problem, in the parser's or the constructor's words
	 */
	InvalidYamlException(String message) {
		super(message);
	}

	/**
	 * Text that is not valid YAML, as the parser reported it.
	 * @param message the problem, in the parser's words
	 * @param cause the parser's exception
	 */
	InvalidYamlException(String message, Throwable cause) {
		super(message, cause);
	}

}
