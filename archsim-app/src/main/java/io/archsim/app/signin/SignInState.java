package io.archsim.app.signin;

/**
 * What a sign-in keeps between sending the browser to the provider and its return.
 *
 * @param provider {@code github} or {@code google}
 * @param state the random value the provider hands back, which must match
 * @param verifier the PKCE verifier the code is exchanged with
 * @param next where to land afterwards
 * @param expiresAt when it stops being good, in seconds since the epoch
 */
record SignInState(String provider, String state, String verifier, String next, double expiresAt) {

}
