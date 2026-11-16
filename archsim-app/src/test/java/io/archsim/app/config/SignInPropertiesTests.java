package io.archsim.app.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The sign-in settings: which providers are set up, who the administrators are, and what
 * signing in needs.
 */
class SignInPropertiesTests {

	private static final String KEY = "secret-key=0123456789abcdef0123456789abcdef";

	private static final String HTTPS = "https://archsim.test";

	@Test
	void github_idAndSecret_areItsCredentials() {
		SignInProperties settings = TestProperties.signIn("github-client-id=h", "github-client-secret=t");

		assertThat(settings.github()).contains(new ClientCredentials("h", "t"));
	}

	@Test
	void google_idWithoutSecret_isNotSetUp() {
		SignInProperties settings = TestProperties.signIn("google-client-id=g");

		assertThat(settings.google()).isEmpty();
	}

	@Test
	void requireComplete_everythingSet_isThePublicUrl() {
		SignInProperties settings = TestProperties.signIn(KEY, "google-client-id=g", "google-client-secret=s");

		assertThat(settings.requireComplete(HTTPS)).isEqualTo(HTTPS);
	}

	@Test
	void sandboxes_notSet_areOff() {
		assertThat(TestProperties.signIn().sandboxes()).isFalse();
	}

	@Test
	void sandboxes_set_areOn() {
		assertThat(TestProperties.signIn("sandboxes=true").sandboxes()).isTrue();
	}

	@Test
	void toString_ofCredentials_masksTheSecret() {
		assertThat(new ClientCredentials("h", "hush").toString()).doesNotContain("hush");
	}

	@Test
	void isAdmin_listedInAnyCase_isTrue() {
		SignInProperties settings = TestProperties.signIn("admin-emails= Admin@Example.com , other@example.com");

		assertThat(settings.isAdmin("admin@example.com")).isTrue();
	}

	@Test
	void isAdmin_nothingListed_isFalse() {
		assertThat(TestProperties.signIn().isAdmin("admin@example.com")).isFalse();
	}

	@Test
	void requireComplete_withoutSecretKey_namesIt() {
		SignInProperties settings = TestProperties.signIn("github-client-id=h", "github-client-secret=t");

		assertThatIllegalStateException().isThrownBy(() -> settings.requireComplete(HTTPS))
			.withMessage("ARCHSIM_AUTH_MODE=oauth needs ARCHSIM_SECRET_KEY (32+ characters)");
	}

	@Test
	void requireComplete_withoutPublicUrl_namesIt() {
		SignInProperties settings = TestProperties.signIn(KEY, "github-client-id=h", "github-client-secret=t");

		assertThatIllegalStateException().isThrownBy(() -> settings.requireComplete("archsim.test"))
			.withMessage("ARCHSIM_AUTH_MODE=oauth needs ARCHSIM_PUBLIC_URL (https://host)");
	}

	@Test
	void requireComplete_withoutProvider_namesThem() {
		SignInProperties settings = TestProperties.signIn(KEY);

		assertThatIllegalStateException().isThrownBy(() -> settings.requireComplete(HTTPS))
			.withMessageStartingWith("ARCHSIM_AUTH_MODE=oauth needs a GitHub or Google client id and secret");
	}

	@Test
	void toString_withSecrets_masksThem() {
		SignInProperties settings = TestProperties.signIn(KEY, "github-client-secret=hush");

		assertThat(settings.toString()).doesNotContain("0123456789abcdef", "hush");
	}

}
