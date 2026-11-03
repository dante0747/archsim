package io.archsim.app.signin;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.app.config.ClientCredentials;

/**
 * A provider people sign in with: OAuth 2's authorization code flow with PKCE.
 *
 * @param name {@code github} or {@code google}, as the sign-in paths name it
 * @param client the OAuth client's id and secret
 * @param endpoints where the browser signs in, the code is exchanged and the account is
 * read
 * @param scope what the API asks to read
 * @param extraParameters what else the sign-in page is told
 * @param format how the provider describes an account
 * @param redirectUri where the provider sends the browser back:
 * {@code <public address>/api/v1/auth/<name>/callback}
 */
record OAuthProvider(String name, ClientCredentials client, Endpoints endpoints, String scope,
		Map<String, String> extraParameters, ProfileFormat format, String redirectUri) {

	/**
	 * Where a provider's steps happen.
	 *
	 * @param authorizeUrl where the browser signs in
	 * @param tokenUrl where the code is exchanged for an access token
	 * @param userUrl where the signed-in account is described
	 * @param emailsUrl where its emails are listed, for a provider that lists them apart
	 */
	record Endpoints(String authorizeUrl, String tokenUrl, String userUrl, @Nullable String emailsUrl) {

	}

}
