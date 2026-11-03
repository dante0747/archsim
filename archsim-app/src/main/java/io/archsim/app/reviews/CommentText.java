package io.archsim.app.reviews;

import java.util.List;

import org.springframework.http.HttpStatus;

import io.archsim.app.store.Text;
import io.archsim.app.web.ApiException;

/**
 * A comment's text, as it is stored: without the white space around it, and not empty.
 */
final class CommentText {

	private CommentText() {
	}

	/**
	 * The text to store for a comment.
	 * @param body the text the request gives
	 * @return the text without the white space around it
	 * @throws ApiException {@code 422} when nothing else is left
	 */
	static String of(String body) {
		String text = Text.strip(body);
		if (text.isEmpty()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "A comment needs some text", List.of());
		}
		return text;
	}

}
