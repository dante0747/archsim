package io.archsim.app.reviews;

import org.jspecify.annotations.Nullable;

/**
 * Someone who wrote, resolved, signed off or changed something.
 *
 * @param id the person's user id
 * @param name their name
 * @param avatarUrl their picture, if any
 */
record PersonView(String id, String name, @Nullable String avatarUrl) {

}
