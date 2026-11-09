package io.archsim.app.signin;

import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Talks OAuth 2 to a provider: its sign-in page's address, with PKCE ({@code S256}), and
 * after the browser's return, the exchange of the code (sent with the verifier, so a
 * stolen code is useless) and the account it signed in. The provider's access token is
 * used for that and dropped.
 */
@Component
class OAuthClient implements DisposableBean {

	private static final Logger LOGGER = LoggerFactory.getLogger(OAuthClient.class);

	/** How long one call to a provider may take. */
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient http;

	private final RestClient rest;

	OAuthClient() {
		// HTTP/1.1: a plain-HTTP provider (GitHub Enterprise inside a network, the
		// black-box suite's stand-in) would otherwise be asked to upgrade to HTTP/2
		// first, and three small requests gain nothing from it
		this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build();
		JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(this.http);
		requests.setReadTimeout(TIMEOUT);
		this.rest = RestClient.builder().requestFactory(requests).build();
	}

	/**
	 * Closes the HTTP client and its connections when the application stops.
	 */
	@Override
	public void destroy() {
		this.http.close();
	}

	/**
	 * The provider's sign-in page for a sign-in.
	 * @param provider the provider
	 * @param state the sign-in, with its state and verifier
	 * @return the page's address
	 */
	String authorizationUrl(OAuthProvider provider, SignInState state) {
		Map<String, String> query = new LinkedHashMap<>();
		query.put("response_type", "code");
		query.put("client_id", provider.client().id());
		query.put("redirect_uri", provider.redirectUri());
		query.put("scope", provider.scope());
		query.put("state", state.state());
		query.put("code_challenge", challenge(state.verifier()));
		query.put("code_challenge_method", "S256");
		query.putAll(provider.extraParameters());
		return provider.endpoints().authorizeUrl() + "?"
				+ query.entrySet()
					.stream()
					.map((parameter) -> encode(parameter.getKey()) + "=" + encode(parameter.getValue()))
					.collect(Collectors.joining("&"));
	}

	/**
	 * Exchanges a code for an access token and reads the account it signed in.
	 * @param provider the provider
	 * @param code the code the provider gave the browser
	 * @param verifier the sign-in's PKCE verifier
	 * @return who signed in
	 * @throws SignInFailure when the provider cannot be reached, refuses the code or
	 * answers something else
	 */
	ProviderProfile profile(OAuthProvider provider, String code, String verifier) {
		try {
			String token = accessToken(provider, code, verifier);
			JsonNode account = get(provider.endpoints().userUrl(), token);
			String emailsUrl = provider.endpoints().emailsUrl();
			JsonNode emails = (emailsUrl != null) ? emails(emailsUrl, token) : JsonNodeFactory.instance.arrayNode();
			return provider.format().read(account, emails);
		}
		catch (RestClientException | UncheckedIOException | HttpMessageNotReadableException ex) {
			LOGGER.warn("Sign-in with {} failed: {}", provider.name(), ex.getClass().getSimpleName());
			throw SignInFailure.provider(ex.getClass().getSimpleName());
		}
	}

	private String accessToken(OAuthProvider provider, String code, String verifier) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("code", code);
		form.add("redirect_uri", provider.redirectUri());
		form.add("client_id", provider.client().id());
		form.add("client_secret", provider.client().secret());
		form.add("code_verifier", verifier);
		JsonNode answer = this.rest.post()
			.uri(provider.endpoints().tokenUrl())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.accept(MediaType.APPLICATION_JSON)
			.body(form)
			.retrieve()
			.body(JsonNode.class);
		JsonNode token = (answer != null) ? answer.path("access_token") : JsonNodeFactory.instance.missingNode();
		if (!token.isString() || token.stringValue().isEmpty()) {
			throw SignInFailure.provider("the provider gave no access token");
		}
		return token.stringValue();
	}

	/**
	 * The account's emails, when the provider shares them (GitHub's {@code user:email}
	 * scope may be refused).
	 * @param url where the provider lists them
	 * @param token the access token
	 * @return the emails, or none
	 */
	private JsonNode emails(String url, String token) {
		try {
			return get(url, token);
		}
		catch (SignInFailure ex) {
			return JsonNodeFactory.instance.arrayNode();
		}
	}

	private JsonNode get(String url, String token) {
		return this.rest.get()
			.uri(url)
			.headers((headers) -> headers.setBearerAuth(token))
			.accept(MediaType.APPLICATION_JSON)
			.exchange((request, response) -> {
				if (response.getStatusCode().value() != HttpStatus.OK.value()) {
					throw SignInFailure.provider(url + " answered HTTP " + response.getStatusCode().value());
				}
				JsonNode body = response.bodyTo(JsonNode.class);
				return (body != null) ? body : JsonNodeFactory.instance.missingNode();
			});
	}

	private static String challenge(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("every Java runtime has SHA-256", ex);
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

}
