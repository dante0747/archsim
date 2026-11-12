package io.archsim.app.reviews;

import io.archsim.model.codec.IgnoreUnknownKeys;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;

/**
 * What {@code PATCH /comments/{id}} takes.
 *
 * @param body the new text
 */
@IgnoreUnknownKeys
record CommentUpdate(@MinLength(1) @MaxLength(CommentCreate.MAX_BODY) String body) {

}
