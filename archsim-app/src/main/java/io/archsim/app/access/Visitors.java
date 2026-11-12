package io.archsim.app.access;

import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

import io.archsim.app.config.ArchsimProperties;

/**
 * Where requests come from: the address a trusted proxy forwards or the peer's, and the
 * browser. A controller takes its request's as a {@link Visitor} parameter; code that
 * runs outside Spring MVC, such as the handlers of Spring Security's sign-in, asks here.
 */
public final class Visitors {

	private final ClientIps clientIps;

	/**
	 * Visitors as the settings' trusted proxies forward them.
	 * @param properties the settings, with {@code ARCHSIM_TRUSTED_PROXIES}
	 * @param clock tells when a proxy's host name is looked up again
	 */
	public Visitors(ArchsimProperties properties, Clock clock) {
		this.clientIps = new ClientIps(new TrustedProxies(properties.trustedProxies().hosts(), clock));
	}

	/**
	 * Where a request comes from.
	 * @param request the request
	 * @return its visitor
	 */
	public Visitor of(HttpServletRequest request) {
		return new Visitor(this.clientIps.of(request).orElse(null), request.getHeader(HttpHeaders.USER_AGENT));
	}

	/**
	 * The addresses requests come from, which the access package's resolvers share.
	 * @return the client addresses
	 */
	ClientIps clientIps() {
		return this.clientIps;
	}

}
