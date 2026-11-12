package io.archsim.app.reviews;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.IgnoreUnknownKeys;

/**
 * What {@code POST /comments/{id}/resolve} takes.
 *
 * @param resolved {@code false} reopens the thread
 */
@IgnoreUnknownKeys
record CommentResolve(@Default("true") boolean resolved) {

}
