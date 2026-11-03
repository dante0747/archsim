package io.archsim.model.validation;

import java.util.Collection;
import java.util.stream.Collectors;

import io.archsim.model.document.RequestType;

/**
 * How the issues of more than one check write the values they name.
 */
final class IssueText {

	private IssueText() {
	}

	/**
	 * Request types as messages list them.
	 * @param types the request types to list
	 * @return their JSON names, joined by commas
	 */
	static String requestTypes(Collection<RequestType> types) {
		return types.stream().map(RequestType::json).collect(Collectors.joining(", "));
	}

}
