package io.archsim.app.signin;

import java.time.Instant;

/**
 * A sandbox's guest, just admitted: an account with no identity or email, which owns the
 * sandbox.
 *
 * @param id the guest's user id
 * @param expiresAt when the guest, their sessions and their sandbox are deleted
 */
record Guest(String id, Instant expiresAt) {

}
