package io.archsim.app.signin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

import io.archsim.app.config.Addresses;
import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.config.ClientCredentials;
import io.archsim.app.config.SignInProperties;
import io.archsim.app.web.ApiException;
import io.archsim.app.web.WebConfiguration;

/**
 * The providers set up here, as Spring Security's {@link CommonOAuth2Provider} defines
 * them, with the site's own addresses. A server with sign-in does not start without a
 * secret key, its public address and a provider, so a broken setup fails at once rather
 * than at the first sign-in. Without sign-in there are none.
 */
@Component
class OAuthProviders {

	private static final String GITHUB = "github";

	private static final String GOOGLE = "google";

	private static final String PROVIDER = "provider";

	/** Every provider's callback path, set up here or not. */
	private static final RequestMatcher CALLBACK = PathPatternRequestMatcher.withDefaults()
		.matcher(WebConfiguration.PREFIX + "/auth/{" + PROVIDER + "}/callback");

	/** The client settings of every provider: PKCE, although the client has a secret. */
	private static final ClientRegistration.ClientSettings PROOF_KEY = ClientRegistration.ClientSettings.builder()
		.requireProofKey(true)
		.build();

	private final ArchsimProperties properties;

	private final Map<String, OAuthProvider> providers;

	OAuthProviders(ArchsimProperties properties, SignInProperties signIn) {
		this.properties = properties;
		this.providers = properties.hosted()
				? providers(signIn, Addresses.withoutTrailingSlashes(signIn.requireComplete(properties.publicUrl())))
				: Map.of();
	}

	/**
	 * A provider people may sign in with here.
	 * @param name the provider's name in a sign-in path
	 * @return the provider, or empty when it is not set up, or nobody signs in here
	 */
	Optional<OAuthProvider> find(String name) {
		return Optional.ofNullable(this.providers.get(name));
	}

	/**
	 * The refusal of a sign-in path whose provider is not set up.
	 * @param name the provider's name in the path
	 * @return {@code 404}, naming it
	 */
	ApiException notSetUp(String name) {
		return new ApiException(HttpStatus.NOT_FOUND, "Sign-in with '" + name + "' is not set up here", List.of());
	}

	/**
	 * The providers as Spring Security's OAuth2 login looks them up.
	 * @return the registrations: none without sign-in
	 */
	ClientRegistrationRepository registrations() {
		return (name) -> find(name).map(OAuthProvider::registration).orElse(null);
	}

	/**
	 * The callback paths of the providers set up here, as a path pattern: a callback for
	 * any other provider reaches {@link SignInController}, which refuses it.
	 * @return the pattern
	 */
	String callbacks() {
		return WebConfiguration.PREFIX + "/auth/{registrationId:" + String.join("|", this.providers.keySet())
				+ "}/callback";
	}

	/**
	 * The provider a callback's path names.
	 * @param request the request
	 * @return the provider's name, or empty when the request is no callback
	 */
	static Optional<String> callbackOf(HttpServletRequest request) {
		return Optional.ofNullable(CALLBACK.matcher(request).getVariables().get(PROVIDER));
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
		signIn.github()
			.ifPresent((client) -> providers.put(GITHUB,
					new OAuthProvider(github(client, signIn, publicUrl), Map.of("allow_signup", "true"))));
		signIn.google()
			.ifPresent((client) -> providers.put(GOOGLE, new OAuthProvider(google(client, publicUrl),
					Map.of("prompt", "select_account", "access_type", "online"))));
		return providers;
	}

	private static ClientRegistration github(ClientCredentials client, SignInProperties signIn, String publicUrl) {
		String site = Addresses.withoutTrailingSlashes(signIn.githubUrl());
		String api = Addresses.withoutTrailingSlashes(signIn.githubApiUrl());
		return registration(CommonOAuth2Provider.GITHUB, GITHUB, client, publicUrl).scope("read:user", "user:email")
			.authorizationUri(site + "/login/oauth/authorize")
			.tokenUri(site + "/login/oauth/access_token")
			.userInfoUri(api + "/user")
			.build();
	}

	/**
	 * Google, through OpenID Connect: its ID token, whose signature, issuer, audience and
	 * nonce Spring Security checks, says who signed in.
	 * @param client the OAuth client's id and secret
	 * @param publicUrl the API's public address
	 * @return the registration
	 */
	private static ClientRegistration google(ClientCredentials client, String publicUrl) {
		return registration(CommonOAuth2Provider.GOOGLE, GOOGLE, client, publicUrl).scope("openid", "email", "profile")
			.build();
	}

	/**
	 * A provider as Spring Security defines it, with this client: its secret is posted in
	 * the token request's body, as GitHub and Google take it, and its key proved (PKCE).
	 * @param provider the provider as Spring Security defines it
	 * @param name the provider's name in the sign-in paths
	 * @param client the OAuth client's id and secret
	 * @param publicUrl the API's public address, where the provider sends the browser
	 * back
	 * @return the registration, to finish
	 */
	private static ClientRegistration.Builder registration(CommonOAuth2Provider provider, String name,
			ClientCredentials client, String publicUrl) {
		return provider.getBuilder(name)
			.clientId(client.id())
			.clientSecret(client.secret())
			.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
			.clientSettings(PROOF_KEY)
			.redirectUri(publicUrl + WebConfiguration.PREFIX + "/auth/" + name + "/callback");
	}

}
