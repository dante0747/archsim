package io.archsim.app.signin;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads who signed in with Google, through OpenID Connect: Spring Security's OIDC user
 * service hands over the claims of the ID token it has checked (and of {@code userinfo},
 * when it reads it), from which ArchSim keeps the account's id, email, whether Google
 * verified it, name and picture.
 */
final class GoogleUsers implements OAuth2UserService<OidcUserRequest, OidcUser> {

	/** The claims ArchSim reads. */
	private static final List<String> CLAIMS = List.of("sub", "email", "email_verified", "name", "picture");

	private final OAuth2UserService<OidcUserRequest, OidcUser> users;

	private final JsonMapper json;

	/**
	 * Google's user service.
	 * @param users the OIDC user service of Spring Security, or one standing in for it
	 * @param json how to read the claims
	 */
	GoogleUsers(OAuth2UserService<OidcUserRequest, OidcUser> users, JsonMapper json) {
		this.users = users;
		this.json = json;
	}

	@Override
	public OidcUser loadUser(OidcUserRequest request) {
		OidcUser user = account(request);
		if (user == null) {
			throw SignInFailure.provider("Google named no account");
		}
		ObjectNode claims = this.json.createObjectNode();
		CLAIMS.forEach((name) -> {
			Object value = user.getClaims().get(name);
			if (value != null) {
				claims.set(name, this.json.valueToTree(value));
			}
		});
		return new GoogleAccount(ProviderProfile.google(claims), user);
	}

	/**
	 * What Spring Security's OIDC user service reads.
	 * @param request the request for the account, with its ID token
	 * @return the user
	 * @throws SignInFailure when Google's answer names no account, which Spring Security
	 * refuses with an {@link IllegalArgumentException}, no failed sign-in
	 */
	private @Nullable OidcUser account(OidcUserRequest request) {
		try {
			return this.users.loadUser(request);
		}
		catch (IllegalArgumentException ex) {
			throw SignInFailure.provider("Google named no account: " + ex.getMessage());
		}
	}

	/**
	 * A Google user, with the profile ArchSim keeps.
	 *
	 * @param profile who signed in
	 * @param user what Spring Security read
	 */
	private record GoogleAccount(ProviderProfile profile, OidcUser user) implements OidcUser, ProviderAccount {

		@Override
		public Map<String, Object> getClaims() {
			return this.user.getClaims();
		}

		@Override
		public @Nullable OidcUserInfo getUserInfo() {
			return this.user.getUserInfo();
		}

		@Override
		public OidcIdToken getIdToken() {
			return this.user.getIdToken();
		}

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
