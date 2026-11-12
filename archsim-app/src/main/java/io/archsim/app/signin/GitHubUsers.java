package io.archsim.app.signin;

import java.util.Collection;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Reads who signed in with GitHub. Spring Security's user service reads {@code /user};
 * this reads {@code /user/emails} with the same access token, for GitHub's rule that an
 * account's email is its primary verified one ({@link ProviderProfile#github}). The
 * person may refuse the {@code user:email} scope, and GitHub then answers the emails with
 * an error: the account has no email. GitHub out of reach, or an answer that names no
 * account, fails the sign-in as the provider's failure.
 */
final class GitHubUsers extends DefaultOAuth2UserService {

	private final RestOperations rest;

	private final JsonMapper json;

	/**
	 * GitHub's user service.
	 * @param rest how to call GitHub, answering an error as Spring Security's OAuth2
	 * client expects ({@link OAuthClients#userInfoClient})
	 * @param json how to read what GitHub answers
	 */
	GitHubUsers(RestOperations rest, JsonMapper json) {
		this.rest = rest;
		this.json = json;
		setRestOperations(rest);
	}

	@Override
	public OAuth2User loadUser(OAuth2UserRequest request) {
		OAuth2User user = account(request);
		JsonNode account = this.json.valueToTree(user.getAttributes());
		return new GitHubAccount(ProviderProfile.github(account, emails(request)), user);
	}

	/**
	 * What {@code /user} says, as Spring Security reads it.
	 * @param request the request for the account, with its access token
	 * @return the user
	 * @throws SignInFailure when GitHub's answer names no account: Spring Security's user
	 * service refuses an empty answer, or one without an {@code id}, with an
	 * {@link IllegalArgumentException}, which is no failed sign-in
	 */
	private OAuth2User account(OAuth2UserRequest request) {
		try {
			return super.loadUser(request);
		}
		catch (IllegalArgumentException ex) {
			throw SignInFailure.provider("GitHub named no account: " + ex.getMessage());
		}
	}

	/**
	 * The account's emails, or none when GitHub refuses them.
	 * @param request the request for the account, with its access token
	 * @return what {@code /user/emails} answered
	 * @throws SignInFailure when GitHub cannot be reached or answers what is not JSON
	 */
	private JsonNode emails(OAuth2UserRequest request) {
		String url = request.getClientRegistration().getProviderDetails().getUserInfoEndpoint().getUri() + "/emails";
		RequestEntity<Void> get = RequestEntity.get(url)
			.headers((headers) -> headers.setBearerAuth(request.getAccessToken().getTokenValue()))
			.accept(MediaType.APPLICATION_JSON)
			.build();
		try {
			JsonNode emails = this.rest.exchange(get, JsonNode.class).getBody();
			return (emails != null) ? emails : JsonNodeFactory.instance.arrayNode();
		}
		catch (RestClientResponseException | OAuth2AuthorizationException ex) {
			return JsonNodeFactory.instance.arrayNode();
		}
		catch (RestClientException ex) {
			throw SignInFailure.provider("GitHub's emails could not be read: " + ex.getClass().getSimpleName());
		}
	}

	/**
	 * A GitHub user, with the profile ArchSim keeps.
	 *
	 * @param profile who signed in
	 * @param user what {@code /user} answered, as Spring Security reads it
	 */
	private record GitHubAccount(ProviderProfile profile, OAuth2User user) implements OAuth2User, ProviderAccount {

		@Override
		public Map<String, Object> getAttributes() {
			return this.user.getAttributes();
		}

		@Override
		public Collection<? extends GrantedAuthority> getAuthorities() {
			return this.user.getAuthorities();
		}

		@Override
		public String getName() {
			return this.profile.subject();
		}

	}

}
