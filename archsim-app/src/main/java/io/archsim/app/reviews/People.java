package io.archsim.app.reviews;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The people some comments, sign-offs or changes name, found by their user id.
 *
 * @param byId each person found
 */
record People(Map<String, PersonView> byId) {

	/**
	 * The person a comment, sign-off or change names.
	 * @param userId their user id, if it names one
	 * @return the person, or {@code null} when it names none or they are gone
	 */
	@Nullable PersonView of(@Nullable String userId) {
		return (userId != null) ? this.byId.get(userId) : null;
	}

}
