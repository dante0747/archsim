package io.archsim.model.issue;

import java.io.Serial;
import java.util.List;

/**
 * A document that cannot be used, with the issues that say why. Its message is the first
 * error's, with how many more there are:
 * {@code Link 'a' references node 'b', which does not exist. (+2 more)}.
 */
public final class DocumentInvalidException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	/** Not serialized: the issues belong to the process that found them. */
	private final transient List<Issue> issues;

	public DocumentInvalidException(List<Issue> issues) {
		super(summary(issues));
		this.issues = List.copyOf(issues);
	}

	/**
	 * Every issue found, in order; at least one is an error.
	 * @return the issues, warnings included
	 */
	public List<Issue> issues() {
		return this.issues;
	}

	private static String summary(List<Issue> issues) {
		List<Issue> errors = issues.stream().filter(Issue::isError).toList();
		String first = errors.isEmpty() ? "invalid document" : errors.getFirst().message();
		return (errors.size() > 1) ? "%s (+%d more)".formatted(first, errors.size() - 1) : first;
	}

}
