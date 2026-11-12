package io.archsim.app.signin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * Checks an OpenID Connect provider's ID tokens (Google's) as Spring Security's
 * {@link OidcIdTokenDecoderFactory} does: RS256, the provider's keys, the default
 * validators (timestamps, issuer, audience, nonce) and claim types, one decoder per
 * provider. Only the keys are fetched over the API's own HTTP client, with its timeouts:
 * the factory makes a client of its own, which waits for ever.
 */
final class IdTokenDecoders implements JwtDecoderFactory<ClientRegistration> {

	private final ClientHttpRequestFactory requests;

	private final Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();

	/**
	 * Decoders that fetch the providers' keys over an HTTP client.
	 * @param requests how the keys are requested
	 */
	IdTokenDecoders(ClientHttpRequestFactory requests) {
		this.requests = requests;
	}

	@Override
	public JwtDecoder createDecoder(ClientRegistration registration) {
		return this.decoders.computeIfAbsent(registration.getRegistrationId(), (provider) -> decoder(registration));
	}

	private JwtDecoder decoder(ClientRegistration registration) {
		String keys = Objects.requireNonNull(registration.getProviderDetails().getJwkSetUri(),
				"an OpenID Connect provider publishes its keys");
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(keys)
			.restOperations(new RestTemplate(this.requests))
			.build();
		decoder.setJwtValidator(
				JwtValidators.createDefaultWithValidators(List.of(new OidcIdTokenValidator(registration))));
		decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
		return decoder;
	}

}
