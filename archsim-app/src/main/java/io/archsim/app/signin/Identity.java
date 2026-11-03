package io.archsim.app.signin;

/**
 * A provider account someone signs in with.
 *
 * @param id the account's id here
 * @param userId the person it belongs to
 */
record Identity(String id, String userId) {

}
