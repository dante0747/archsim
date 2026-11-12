package io.archsim.app.signin;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * How the API calls the providers when a browser comes back from one: the token request
 * that exchanges the code, sent with the PKCE verifier, the user services that read the
 * account, and the keys that Google's ID tokens are checked with. Spring Security's
 * OAuth2 login finds these beans by their types. Every call has ten seconds, over
 * HTTP/1.1: a plain-HTTP provider (GitHub Enterprise inside a network, the black-box
 * suite's stand-in) would otherwise be asked to upgrade to HTTP/2 first, and a few small
 * requests gain nothing from it.
 */
@Configuration(proxyBeanMethods = false)
class OAuthClients implements DisposableBean {

	/** How long one call to a provider may take. */
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient http;

	private final JdkClientHttpRequestFactory requests;

	OAuthClients() {
		this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build();
		this.requests = new JdkClientHttpRequestFactory(this.http);
		this.requests.setReadTimeout(TIMEOUT);
	}

	/**
	 * Exchanges a code for an access token, as Spring Security's client does, over this
	 * HTTP client.
	 * @return the token client
	 */
	@Bean
	OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> accessTokenResponseClient() {
		RestClientAuthorizationCodeTokenResponseClient tokens = new RestClientAuthorizationCodeTokenResponseClient();
		tokens.setRestClient(
				RestClient.builder().requestFactory(this.requests).configureMessageConverters((converters) -> {
					converters.addCustomConverter(new FormHttpMessageConverter());
					converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
				}).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
		return tokens;
	}

	/**
	 * Reads who signed in with GitHub.
	 * @param json the application's JSON mapper
	 * @return the user service
	 */
	@Bean
	GitHubUsers gitHubUsers(JsonMapper json) {
		return new GitHubUsers(userInfoClient(this.requests), json);
	}

	/**
	 * Reads who signed in with Google, through OpenID Connect.
	 * @param json the application's JSON mapper
	 * @return the user service
	 */
	@Bean
	GoogleUsers googleUsers(JsonMapper json) {
		DefaultOAuth2UserService userInfo = new DefaultOAuth2UserService();
		userInfo.setRestOperations(userInfoClient(this.requests));
		OidcUserService users = new OidcUserService();
		users.setOauth2UserService(userInfo);
		return new GoogleUsers(users, json);
	}

	/**
	 * Checks an OpenID Connect provider's ID tokens, fetching its keys over this HTTP
	 * client.
	 * @return the decoders
	 */
	@Bean
	JwtDecoderFactory<ClientRegistration> idTokenDecoders() {
		return new IdTokenDecoders(this.requests);
	}

	/**
	 * A client for a provider's user endpoints that answers an error as Spring Security's
	 * user services expect.
	 * @param requests how it sends its requests
	 * @return the client
	 */
	static RestTemplate userInfoClient(ClientHttpRequestFactory requests) {
		RestTemplate rest = new RestTemplate(requests);
		rest.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
		return rest;
	}

	/**
	 * Closes the HTTP client and its connections when the application stops.
	 */
	@Override
	public void destroy() {
		this.http.close();
	}

}
