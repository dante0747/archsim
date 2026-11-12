package io.archsim.app.web;

import java.util.Arrays;

import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import io.archsim.app.config.ArchsimProperties;

/**
 * The headers that keep API answers from being framed, sniffed, cached or leaked, which
 * Spring Security's chain writes on every response: the ones the Python API set, and no
 * others of Spring Security's defaults. A handler that sets one of them itself wins, but
 * for {@code X-Frame-Options}, which Spring Security's writer always sets. The
 * interactive docs load their scripts, so they keep the browser's defaults for content;
 * the private routes are never cached; and an API served over HTTPS tells browsers to
 * keep to it on every answer, as TLS ends before the request reaches it.
 */
public final class SecurityHeaders implements Customizer<HeadersConfigurer<HttpSecurity>> {

	private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

	private static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=()";

	/** The routes that say who you are and what you hold, which no cache keeps. */
	private static final RequestMatcher PRIVATE = under("auth", "tokens", "workspaces", "invitations");

	/** The interactive docs, whose page loads its scripts from elsewhere. */
	private static final RequestMatcher DOCS = under("docs", "swagger-ui");

	private final boolean strictTransport;

	/**
	 * The headers for the API these settings describe.
	 * @param properties the settings: HSTS goes with an {@code https} public address
	 */
	public SecurityHeaders(ArchsimProperties properties) {
		this.strictTransport = properties.servedOverHttps();
	}

	@Override
	public void customize(HeadersConfigurer<HttpSecurity> headers) {
		headers.defaultsDisabled()
			.contentTypeOptions(Customizer.withDefaults())
			.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
			.referrerPolicy((referrer) -> referrer.policy(ReferrerPolicy.NO_REFERRER))
			.crossOriginResourcePolicy((resources) -> resources.policy(CrossOriginResourcePolicy.SAME_ORIGIN))
			.permissionsPolicyHeader((permissions) -> permissions.policy(PERMISSIONS_POLICY))
			.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(new NegatedRequestMatcher(DOCS),
					new ContentSecurityPolicyHeaderWriter(CONTENT_SECURITY_POLICY)))
			.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(PRIVATE,
					new StaticHeadersWriter(HttpHeaders.CACHE_CONTROL, "no-store")));
		if (this.strictTransport) {
			headers.httpStrictTransportSecurity((transport) -> transport.requestMatcher(AnyRequestMatcher.INSTANCE));
		}
	}

	private static RequestMatcher under(String... sections) {
		PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
		return new OrRequestMatcher(Arrays.stream(sections)
			.map((section) -> paths.matcher(WebConfiguration.PREFIX + "/" + section + "/**"))
			.toArray(RequestMatcher[]::new));
	}

}
