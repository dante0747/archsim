package io.archsim.app.signin;

/**
 * A person who signs in.
 *
 * @param id the person's user id
 * @param disabled whether they may no longer sign in
 */
record Account(String id, boolean disabled) {

}
