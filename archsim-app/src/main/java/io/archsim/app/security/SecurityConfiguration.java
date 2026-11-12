package io.archsim.app.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.web.SecurityHeaders;

/**
 * Spring Security's filter chain (ADR-0065). It authorizes every request: an endpoint
 * says whom it needs through its parameters, so public endpoints, unknown paths and body
 * errors answer as before. It puts who is asking in the security context
 * ({@link RequestAuthentication}): the API token a request carries, refused wherever it
 * is sent when it is wrong, or its browser session, whose changes need its CSRF token;
 * without sign-in, the local user. The chain writes the security headers, runs the API's
 * CORS filter (the {@code corsFilter} bean), so that a browser on an allowed origin can
 * read the chain's refusals too, and guards against malformed paths. It leaves out what
 * Spring Security does for a site with its own login page: a login and a logout page, the
 * saved request and the anonymous user. Only the {@code api} role serves HTTP, so only it
 * has the chain.
 *
 * <p>
 * The API keeps no HTTP sessions, and a browser session is read only when an endpoint
 * asks who is asking, or a change that carries its cookie needs its CSRF token. So
 * session management is off, whose filter reads every request's context (with it goes its
 * filter against session ids in URLs, which nothing here makes); and so is the servlet
 * API's view of who is asking, whose {@code getUserPrincipal()} Spring MVC calls for
 * every request it handles.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfiguration {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, ArchsimProperties properties) {
		return http.authorizeHttpRequests((requests) -> requests.anyRequest().permitAll())
			.headers(new SecurityHeaders(properties))
			.cors(Customizer.withDefaults())
			.sessionManagement(AbstractHttpConfigurer::disable)
			.servletApi(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.anonymous(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.with(new RequestAuthentication(properties))
			.build();
	}

	/**
	 * Spring Security's request firewall, with its defaults but for two paths the API
	 * served before the firewall came: one with an encoded slash, which Tomcat decodes as
	 * Starlette did (ADR-0034), and one with a path parameter, which Spring MVC leaves
	 * out of the route. The firewall refuses them because a rule that matches paths could
	 * read them differently from the route. This chain's rules (the private routes and
	 * the docs in {@link SecurityHeaders}) are {@code PathPatternRequestMatcher}s, which
	 * parse the path as Spring MVC routes it, so the two agree; a rule matched any other
	 * way would need these two refused again.
	 * @return the firewall
	 */
	@Bean
	HttpFirewall firewall() {
		StrictHttpFirewall firewall = new StrictHttpFirewall();
		firewall.setAllowUrlEncodedSlash(true);
		firewall.setAllowSemicolon(true);
		return firewall;
	}

}
