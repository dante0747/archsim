package io.archsim.app.signin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.config.ClientCredentials;
import io.archsim.app.config.SignInProperties;
import io.archsim.app.web.ApiException;

/**
 * The providers set up here. A server with sign-in does not start without a secret key,
 * its public address and a provider, so a broken setup fails at once rather than at the
 * first sign-in. Without sign-in there are none.
 */
@Component
class OAuthProviders {

	private static final String GITHUB = "github";

	private static final String GOOGLE = "google";

	private static final OAuthProvider.Endpoints GOOGLE_ENDPOINTS = new OAuthProvider.Endpoints(
			"https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
			"https://openidconnect.googleapis.com/v1/userinfo", null);

	private final ArchsimProperties properties;

	private final Map<String, OAuthProvider> providers;

	OAuthProviders(ArchsimProperties properties, SignInProperties signIn) {
		this.properties = properties;
		this.providers = properties.hosted()
				? providers(signIn, trimmed(signIn.requireComplete(properties.publicUrl()))) : Map.of();
	}

	/**
	 * A provider people may sign in with here.
	 * @param name {@code github} or {@code google}
	 * @return the provider
	 * @throws ApiException {@code 404} when it is not set up, or nobody signs in here
	 */
	OAuthProvider require(String name) {
		OAuthProvider provider = this.providers.get(name);
		if (provider == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "Sign-in with '" + name + "' is not set up here", List.of());
		}
		return provider;
	}

	/**
	 * How people sign in here.
	 * @return the mode and the providers sign-in offers, GitHub first
	 */
	AuthConfig config() {
		return new AuthConfig(this.properties.authMode().json(), List.copyOf(this.providers.keySet()));
	}

	/**
	 * Whether people sign in here.
	 * @return {@code true} with {@code ARCHSIM_AUTH_MODE=oauth}
	 */
	boolean hosted() {
		return this.properties.hosted();
	}

	private static Map<String, OAuthProvider> providers(SignInProperties signIn, String publicUrl) {
		Map<String, OAuthProvider> providers = new LinkedHashMap<>();
		signIn.github().ifPresent((client) -> providers.put(GITHUB, github(client, signIn, publicUrl)));
		signIn.google()
			.ifPresent((client) -> providers.put(GOOGLE, new OAuthProvider(GOOGLE, client, GOOGLE_ENDPOINTS,
					"openid email profile", googleParameters(), ProfileFormat.GOOGLE, callback(publicUrl, GOOGLE))));
		return providers;
	}

	private static OAuthProvider github(ClientCredentials client, SignInProperties signIn, String publicUrl) {
		String site = trimmed(signIn.githubUrl());
		String api = trimmed(signIn.githubApiUrl());
		OAuthProvider.Endpoints endpoints = new OAuthProvider.Endpoints(site + "/login/oauth/authorize",
				site + "/login/oauth/access_token", api + "/user", api + "/user/emails");
		return new OAuthProvider(GITHUB, client, endpoints, "read:user user:email", Map.of("allow_signup", "true"),
				ProfileFormat.GITHUB, callback(publicUrl, GITHUB));
	}

	private static String callback(String publicUrl, String provider) {
		return publicUrl + "/api/v1/auth/" + provider + "/callback";
	}

	private static Map<String, String> googleParameters() {
		Map<String, String> parameters = new LinkedHashMap<>();
		parameters.put("prompt", "select_account");
		parameters.put("access_type", "online");
		return parameters;
	}

	private static String trimmed(String url) {
		int end = url.length();
		while (end > 0 && url.charAt(end - 1) == '/') {
			end--;
		}
		return url.substring(0, end);
	}

}
