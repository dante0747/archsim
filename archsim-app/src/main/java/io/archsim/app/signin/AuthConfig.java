package io.archsim.app.signin;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * How people sign in here.
 *
 * @param mode {@code none} (local, no sign-in) or {@code oauth}
 * @param providers the providers sign-in offers, GitHub first
 * @param sandboxes whether a visitor may try ArchSim without signing in, in a sandbox
 * @param csrfToken where sandboxes are offered, the browser's CSRF token, which starting
 * one sends back as {@code X-ArchSim-CSRF}; {@code null} elsewhere
 */
record AuthConfig(String mode, List<String> providers, boolean sandboxes, @Nullable String csrfToken) {

}
