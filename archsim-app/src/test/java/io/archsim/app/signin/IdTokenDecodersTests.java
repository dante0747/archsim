package io.archsim.app.signin;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import io.archsim.app.config.TestProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Google's ID tokens, checked as Spring Security checks them: signed by one of Google's
 * keys, which are fetched over the API's client, issued by Google and for this client.
 */
class IdTokenDecodersTests {

	private static final ClientRegistration GOOGLE = new OAuthProviders(
			TestProperties.with("auth-mode=oauth", "public-url=https://archsim.test"),
			TestProperties.signIn("secret-key=test-secret-key-0123456789-abcdefghijklmnop", "google-client-id=g-id",
					"google-client-secret=g-secret"))
		.find("google")
		.orElseThrow()
		.registration();

	private static final String GOOGLES_ISSUER = "https://accounts.google.com";

	private static final int KEY_BITS = 2048;

	private static final long LIFETIME_SECONDS = 60;

	private final RSAKey googlesKey = key();

	private final List<URI> fetched = new CopyOnWriteArrayList<>();

	private final IdTokenDecoders decoders = new IdTokenDecoders(keysOf(this.googlesKey));

	@Test
	void createDecoder_tokenSignedByGoogle_isDecodedWithItsClaimTypes() throws JOSEException {
		Jwt decoded = this.decoders.createDecoder(GOOGLE).decode(token(this.googlesKey, GOOGLES_ISSUER, "g-id"));

		assertThat(decoded.getSubject()).isEqualTo("1234");
		assertThat(decoded.<Object>getClaim("email_verified")).isInstanceOf(Boolean.class).isEqualTo(true);
	}

	@Test
	void createDecoder_keys_areFetchedOverTheApisClientFromGoogle() throws JOSEException {
		this.decoders.createDecoder(GOOGLE).decode(token(this.googlesKey, GOOGLES_ISSUER, "g-id"));

		assertThat(this.fetched)
			.containsExactly(URI.create(Objects.requireNonNull(GOOGLE.getProviderDetails().getJwkSetUri())));
	}

	@Test
	void createDecoder_tokenForAnotherClient_isRefused() throws JOSEException {
		String token = token(this.googlesKey, GOOGLES_ISSUER, "someone-else");

		assertThatThrownBy(() -> this.decoders.createDecoder(GOOGLE).decode(token))
			.isInstanceOf(JwtValidationException.class);
	}

	@Test
	void createDecoder_tokenFromAnotherIssuer_isRefused() throws JOSEException {
		String token = token(this.googlesKey, "https://issuer.example", "g-id");

		assertThatThrownBy(() -> this.decoders.createDecoder(GOOGLE).decode(token))
			.isInstanceOf(JwtValidationException.class);
	}

	@Test
	void createDecoder_tokenSignedByAnotherKey_isRefused() throws JOSEException {
		String token = token(key(), GOOGLES_ISSUER, "g-id");

		assertThatThrownBy(() -> this.decoders.createDecoder(GOOGLE).decode(token)).isInstanceOf(BadJwtException.class)
			.hasMessageContaining("Invalid signature");
	}

	@Test
	void createDecoder_sameProviderTwice_isTheSameDecoder() {
		assertThat(this.decoders.createDecoder(GOOGLE)).isSameAs(this.decoders.createDecoder(GOOGLE));
	}

	/** An RSA key with Google's key id, so only its signature tells it apart. */
	private static RSAKey key() {
		try {
			return new RSAKeyGenerator(KEY_BITS).keyID("google-key").generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * An ID token for subject 1234 with a verified email, signed with a key. It is issued
	 * now, not at a fixed time: Spring Security's timestamp check reads the system clock.
	 */
	private static String token(RSAKey key, String issuer, String audience) throws JOSEException {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer)
			.audience(audience)
			.subject("1234")
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plusSeconds(LIFETIME_SECONDS)))
			.claim("email_verified", true)
			.build();
		SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
				claims);
		token.sign(new RSASSASigner(key));
		return token.serialize();
	}

	/** An HTTP client that answers every request with a key set, noting where it went. */
	private ClientHttpRequestFactory keysOf(RSAKey key) {
		byte[] keys = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
		return (uri, method) -> {
			this.fetched.add(uri);
			MockClientHttpResponse response = new MockClientHttpResponse(keys, HttpStatus.OK);
			response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
			MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
			request.setResponse(response);
			return request;
		};
	}

}
