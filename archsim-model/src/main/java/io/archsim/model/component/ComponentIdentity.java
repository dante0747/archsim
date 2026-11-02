package io.archsim.model.component;

import java.util.Objects;

/**
 * What names a component type: its name in documents, the name people see, and the kind
 * of part it is.
 *
 * @param type the type's name in documents, such as {@code service}
 * @param displayName the name people see, such as {@code Service / API Servers}
 * @param category the kind of part the type is
 */
public record ComponentIdentity(String type, String displayName, Category category) {

	public ComponentIdentity {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(category, "category");
	}

}
