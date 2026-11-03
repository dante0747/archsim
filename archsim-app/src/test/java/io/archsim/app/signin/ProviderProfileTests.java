package io.archsim.app.signin;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who signed in, from what GitHub and Google say: only a verified email is trusted.
 */
class ProviderProfileTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNode USER = JSON.readTree("""
			{"id": 42, "login": "ada", "name": "Ada Lovelace", "avatar_url": "https://avatars.example.com/42"}""");

	@Test
	void github_primaryVerifiedEmail_isTheEmailInLowerCase() {
		ProviderProfile profile = ProviderProfile.github(USER, JSON.readTree("""
				[{"email": "old@example.com", "verified": true, "primary": false},
				 {"email": "Ada@Example.com", "verified": true, "primary": true}]"""));

		assertThat(profile.email()).isEqualTo("ada@example.com");
		assertThat(profile.emailVerified()).isTrue();
	}

	@Test
	void github_onlyUnverifiedEmails_hasNoEmail() {
		ProviderProfile profile = ProviderProfile.github(USER, JSON.readTree("""
				[{"email": "ada@example.com", "verified": false, "primary": true}]"""));

		assertThat(profile.email()).isNull();
		assertThat(profile.emailVerified()).isFalse();
	}

	@Test
	void github_user_isItsIdNameLoginAndAvatar() {
		ProviderProfile profile = ProviderProfile.github(USER, JSON.createArrayNode());

		assertThat(profile)
			.isEqualTo(new ProviderProfile("42", "Ada Lovelace", null, false, "https://avatars.example.com/42", "ada"));
	}

	@Test
	void github_withoutName_isNamedByItsLogin() {
		ProviderProfile profile = ProviderProfile.github(JSON.readTree("""
				{"id": 7, "login": "bob", "name": null}"""), JSON.createArrayNode());

		assertThat(profile.name()).isEqualTo("bob");
	}

	@Test
	void google_verifiedEmail_isTrusted() {
		ProviderProfile profile = ProviderProfile.google(JSON.readTree("""
				{"sub": "g-1", "email": "Ada@Example.com", "email_verified": true, "picture": "https://p/1"}"""));

		assertThat(profile)
			.isEqualTo(new ProviderProfile("g-1", "Ada@Example.com", "ada@example.com", true, "https://p/1", null));
	}

	@Test
	void google_unverifiedEmail_isKeptButNotTrusted() {
		ProviderProfile profile = ProviderProfile.google(JSON.readTree("""
				{"sub": "g-2", "name": "Mallory", "email": "ada@example.com", "email_verified": false}"""));

		assertThat(profile.emailVerified()).isFalse();
		assertThat(profile.email()).isEqualTo("ada@example.com");
	}

}
