package io.archsim.app.reviews;

import io.archsim.model.codec.Default;

/**
 * What {@code POST /comments/{id}/resolve} takes.
 *
 * @param resolved {@code false} reopens the thread
 */
record CommentResolve(@Default("true") boolean resolved) {

}
