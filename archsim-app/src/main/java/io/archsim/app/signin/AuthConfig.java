package io.archsim.app.signin;

import java.util.List;

/**
 * How people sign in here.
 *
 * @param mode {@code none} (local, no sign-in) or {@code oauth}
 * @param providers the providers sign-in offers, GitHub first
 */
record AuthConfig(String mode, List<String> providers) {

}
