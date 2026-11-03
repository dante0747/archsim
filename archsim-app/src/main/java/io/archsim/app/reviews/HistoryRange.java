package io.archsim.app.reviews;

import java.util.List;
import java.util.OptionalLong;

import org.jspecify.annotations.Nullable;

import io.archsim.app.web.QueryParameters;
import io.archsim.app.web.RequestInvalidException;

/**
 * Which changes of the history to read: a page of them, older than one.
 *
 * @param before only changes older than this id; none for the newest
 * @param limit how many at most
 */
record HistoryRange(@Nullable Long before, int limit) {

	/** Changes a page holds unless the request says otherwise. */
	static final int DEFAULT_LIMIT = 50;

	/** The most changes a page holds. */
	static final int MAX_LIMIT = 200;

	/**
	 * The range a query asks for.
	 * @param before the {@code before} parameter's values, if any
	 * @param limit the {@code limit} parameter's values, if any
	 * @return the range
	 * @throws RequestInvalidException {@code 422} for a {@code before} below 1, or a
	 * {@code limit} outside 1 to 200
	 */
	static HistoryRange of(@Nullable List<String> before, @Nullable List<String> limit) {
		OptionalLong older = QueryParameters.integer(before, "before", 1);
		long size = QueryParameters.integer(limit, "limit", 1, MAX_LIMIT).orElse(DEFAULT_LIMIT);
		return new HistoryRange(older.isPresent() ? older.getAsLong() : null, Math.toIntExact(size));
	}

}
