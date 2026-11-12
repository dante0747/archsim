/**
 * Spring Security's filter chain (ADR-0065): the request firewall, the security headers,
 * CORS and, with sign-in, API tokens, in front of every endpoint, which still says whom
 * it needs.
 */
@NullMarked
package io.archsim.app.security;

import org.jspecify.annotations.NullMarked;
