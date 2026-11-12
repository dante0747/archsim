package io.archsim.app.security;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadata;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

import io.archsim.app.access.ApiAuthenticationEntryPoint;
import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.web.ErrorResponses;
import io.archsim.app.web.WebConfiguration;

/**
 * Personal API tokens through Spring Security's resource server: a bearer token in
 * {@code Authorization}, read from the {@code api_tokens} table. A wrong one is refused
 * wherever it is sent, with the API's {@code 401} (ADR-0065); a request without one goes
 * on to the endpoint. The protected resource metadata (RFC 9728) names the API and says
 * tokens go in the header, and in nothing else.
 */
final class ApiTokens implements Customizer<OAuth2ResourceServerConfigurer<HttpSecurity>> {

	private final @Nullable String resource;

	private final ObjectProvider<OpaqueTokenIntrospector> introspectors;

	private final ErrorResponses responses;

	/**
	 * Tokens read by the introspector the application has.
	 * @param properties the settings, whose public address names the API, if set
	 * @param introspectors the token introspector, looked up at the first token, so the
	 * web layer's test slices, which have no database, still start
	 * @param responses writes a refusal as the API writes its errors
	 */
	ApiTokens(ArchsimProperties properties, ObjectProvider<OpaqueTokenIntrospector> introspectors,
			ErrorResponses responses) {
		this.resource = properties.publicAddress().map((address) -> address + WebConfiguration.PREFIX).orElse(null);
		this.introspectors = introspectors;
		this.responses = responses;
	}

	@Override
	public void customize(OAuth2ResourceServerConfigurer<HttpSecurity> tokens) {
		tokens.opaqueToken((opaque) -> opaque.introspector(this::introspect))
			.authenticationEntryPoint(new ApiAuthenticationEntryPoint(this.responses))
			.protectedResourceMetadata((metadata) -> metadata.protectedResourceMetadataCustomizer(this::describe));
	}

	/**
	 * The protected resource metadata: the API, and no client certificates bound to its
	 * tokens.
	 * @param document the document, which already says tokens go in the header
	 */
	private void describe(OAuth2ProtectedResourceMetadata.Builder document) {
		if (this.resource != null) {
			document.resource(this.resource);
		}
		document.tlsClientCertificateBoundAccessTokens(false);
	}

	private OAuth2AuthenticatedPrincipal introspect(String token) {
		return this.introspectors.getObject().introspect(token);
	}

}
