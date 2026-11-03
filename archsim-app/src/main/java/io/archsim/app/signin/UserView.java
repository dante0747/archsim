package io.archsim.app.signin;

import org.jspecify.annotations.Nullable;

/**
 * A person, as others see them.
 *
 * @param id the person's user id
 * @param name their name
 * @param email their verified email, if any
 * @param avatarUrl their picture, if any
 */
record UserView(String id, String name, @Nullable String email, @Nullable String avatarUrl) {

}
