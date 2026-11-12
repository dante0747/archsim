package io.archsim.app.signin;

import java.util.Map;

import org.springframework.security.oauth2.client.registration.ClientRegistration;

/**
 * A provider people sign in with: OAuth 2's authorization code flow with PKCE, as Spring
 * Security's OAuth2 login runs it.
 *
 * @param registration the provider as Spring Security knows it: the client's id and
 * secret, sent in the token request's body; where the browser signs in, the code is
 * exchanged and the account is read; what the API asks to read; and where the provider
 * sends the browser back, {@code <public address>/api/v1/auth/<name>/callback}
 * @param signInParameters what else the provider's sign-in page is told
 */
record OAuthProvider(ClientRegistration registration, Map<String, String> signInParameters) {

	OAuthProvider {
		signInParameters = Map.copyOf(signInParameters);
	}

	/**
	 * The provider's name, as the sign-in paths name it.
	 * @return {@code github} or {@code google}
	 */
	String name() {
		return this.registration.getRegistrationId();
	}

}
