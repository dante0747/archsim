package io.archsim.app.security;

import org.springframework.context.ApplicationContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.access.BrowserSessions;
import io.archsim.app.access.LocalUserFilter;
import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.web.ErrorResponses;

/**
 * Who is asking, as the chain puts it in Spring Security's context for each request
 * (ADR-0065): with sign-in, the API token a request carries (the resource server,
 * {@link ApiTokens}) or its browser session ({@link BrowserSessions}, read when an
 * endpoint first asks); without, the local user ({@link LocalUserFilter}). Either way the
 * context lives as long as its request. A configurer of the chain of its own, so it finds
 * the application's beans as Spring Security's configurers do.
 */
final class RequestAuthentication extends AbstractHttpConfigurer<RequestAuthentication, HttpSecurity> {

	private final ArchsimProperties properties;

	/**
	 * Authentication as the settings have it.
	 * @param properties the settings: whether people sign in, and the public address
	 */
	RequestAuthentication(ArchsimProperties properties) {
		this.properties = properties;
	}

	@Override
	public void init(HttpSecurity http) {
		ApplicationContext context = http.getSharedObject(ApplicationContext.class);
		http.securityContext((security) -> security.securityContextRepository(contextRepository(context)));
		if (this.properties.hosted()) {
			http.oauth2ResourceServer(
					new ApiTokens(this.properties, context.getBeanProvider(OpaqueTokenIntrospector.class),
							new ErrorResponses(context.getBean(JsonMapper.class))));
		}
	}

	@Override
	public void configure(HttpSecurity http) {
		if (!this.properties.hosted()) {
			http.addFilterAfter(new LocalUserFilter(getSecurityContextHolderStrategy()),
					SecurityContextHolderFilter.class);
		}
	}

	/**
	 * Where a request's context comes from: with sign-in, its browser session; else, or
	 * in the web layer's test slices, which have no database and so no sessions to read,
	 * the request itself.
	 * @param context the application
	 * @return the repository
	 */
	private SecurityContextRepository contextRepository(ApplicationContext context) {
		BrowserSessions sessions = this.properties.hosted()
				? context.getBeanProvider(BrowserSessions.class).getIfAvailable() : null;
		return (sessions != null) ? sessions : new RequestAttributeSecurityContextRepository();
	}

}
