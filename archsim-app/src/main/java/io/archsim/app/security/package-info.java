/**
 * Spring Security's filter chain (ADR-0065): the request firewall, the security headers
 * and CORS in front of every endpoint, which still says whom it needs; and who is asking,
 * in the security context: with sign-in, an API token or a browser session, with CSRF on
 * the session's changes; without, the local user.
 */
@NullMarked
package io.archsim.app.security;

import org.jspecify.annotations.NullMarked;
