package io.archsim.app.reviews;

import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.util.MultiValueMap;

import io.archsim.app.web.QueryParameters;
import io.archsim.app.web.RequestInvalidException;

/**
 * Which of a project's comments to list: those on one part, run or the project.
 *
 * @param anchorKind only comments on this kind of thing
 * @param anchorId only comments on this part or run
 * @param runId only comments about this run
 */
record CommentFilter(@Nullable String anchorKind, @Nullable String anchorId, @Nullable String runId) {

	/**
	 * The comments a query asks for: {@code anchorKind}, {@code anchorId} and
	 * {@code runId}, each read from the query string only.
	 * @param query the request's query parameters
	 * @return the filter
	 * @throws RequestInvalidException {@code 422} for an unknown kind, or an id longer
	 * than any
	 */
	static CommentFilter of(MultiValueMap<String, String> query) {
		List<String> kinds = Arrays.stream(AnchorKind.values()).map(AnchorKind::json).toList();
		return new CommentFilter(QueryParameters.choice(query.get("anchorKind"), "anchorKind", kinds).orElse(null),
				QueryParameters.text(query.get("anchorId"), "anchorId", CommentCreate.MAX_ANCHOR_ID).orElse(null),
				QueryParameters.text(query.get("runId"), "runId", CommentCreate.MAX_ID).orElse(null));
	}

}
