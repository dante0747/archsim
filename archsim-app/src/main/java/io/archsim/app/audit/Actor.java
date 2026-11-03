package io.archsim.app.audit;

import org.jspecify.annotations.Nullable;

/**
 * Who made a change.
 *
 * @param userId the user, if a user made it
 * @param kind {@code user}, {@code token} or {@code system}
 * @param ip the address the change came from, if known
 */
record Actor(@Nullable String userId, String kind, @Nullable String ip) {

}
