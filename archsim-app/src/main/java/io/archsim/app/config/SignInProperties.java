package io.archsim.app.config;

import java.util.Optional;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import io.archsim.app.store.Text;

/**
 * The settings of signing in with GitHub or Google ({@code ARCHSIM_AUTH_MODE=oauth}),
 * read from the same {@code ARCHSIM_*} environment variables as the Python API's.
 *
 * @param secretKey signs the sign-in state cookie and derives CSRF tokens: at least 32
 * random characters, the same on every API instance
 * @param adminEmails verified emails whose first sign-in makes them an owner of the
 * default workspace
 * @param githubClientId the GitHub OAuth app's client id
 * @param githubClientSecret the GitHub OAuth app's client secret
 * @param githubUrl GitHub's address (GitHub Enterprise Server: its own)
 * @param githubApiUrl GitHub's API (GitHub Enterprise Server: {@code <address>/api/v3})
 * @param googleClientId the Google OAuth client's id
 * @param googleClientSecret the Google OAuth client's secret
 * @param sessionDays how long a sign-in lasts
 */
@ConfigurationProperties("archsim")
@Validated
public record SignInProperties(@Nullable String secretKey, @Nullable AdminEmails adminEmails,
		@Nullable String githubClientId, @Nullable String githubClientSecret,
		@DefaultValue("https://github.com") String githubUrl,
		@DefaultValue("https://api.github.com") String githubApiUrl, @Nullable String googleClientId,
		@Nullable String googleClientSecret, @DefaultValue("30") @Min(1) @Max(MAX_SESSION_DAYS) int sessionDays) {

	/** The longest a sign-in may last. */
	static final int MAX_SESSION_DAYS = 365;

	/** The shortest secret key that signs anything. */
	private static final int MIN_SECRET_KEY = 32;

	/**
	 * The GitHub OAuth app, when its id and secret are both set.
	 * @return its credentials, or empty when sign-in with GitHub is not set up
	 */
	public Optional<ClientCredentials> github() {
		return credentials(this.githubClientId, this.githubClientSecret);
	}

	/**
	 * The Google OAuth client, when its id and secret are both set.
	 * @return its credentials, or empty when sign-in with Google is not set up
	 */
	public Optional<ClientCredentials> google() {
		return credentials(this.googleClientId, this.googleClientSecret);
	}

	/**
	 * Whether a verified email is an administrator's.
	 * @param email the email, lower case
	 * @return {@code true} when {@code ARCHSIM_ADMIN_EMAILS} lists it
	 */
	public boolean isAdmin(String email) {
		return this.adminEmails != null && this.adminEmails.includes(email);
	}

	/**
	 * Checks that sign-in can work: a secret key, the public address callbacks go to, and
	 * a provider, with the Python API's messages.
	 * @param publicUrl {@code ARCHSIM_PUBLIC_URL}
	 * @return the public address, which is set
	 * @throws IllegalStateException naming the first setting that is missing
	 */
	public String requireComplete(@Nullable String publicUrl) {
		if (this.secretKey == null || Text.length(this.secretKey) < MIN_SECRET_KEY) {
			throw new IllegalStateException("ARCHSIM_AUTH_MODE=oauth needs ARCHSIM_SECRET_KEY (32+ characters)");
		}
		if (publicUrl == null || !(publicUrl.startsWith("https://") || publicUrl.startsWith("http://"))) {
			throw new IllegalStateException("ARCHSIM_AUTH_MODE=oauth needs ARCHSIM_PUBLIC_URL (https://host)");
		}
		if (github().isEmpty() && google().isEmpty()) {
			throw new IllegalStateException("ARCHSIM_AUTH_MODE=oauth needs a GitHub or Google client id and secret "
					+ "(ARCHSIM_GITHUB_CLIENT_ID/SECRET, ARCHSIM_GOOGLE_CLIENT_ID/SECRET)");
		}
		return publicUrl;
	}

	private static Optional<ClientCredentials> credentials(@Nullable String id, @Nullable String secret) {
		if (id == null || id.isEmpty() || secret == null || secret.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new ClientCredentials(id, secret));
	}

	/**
	 * The settings, with the secrets masked.
	 * @return the settings, fit for a log
	 */
	@Override
	public String toString() {
		return ("SignInProperties[secretKey=%s, adminEmails=%s, githubClientId=%s, githubClientSecret=%s, "
				+ "githubUrl=%s, githubApiUrl=%s, googleClientId=%s, googleClientSecret=%s, sessionDays=%s]")
			.formatted(masked(this.secretKey), this.adminEmails, this.githubClientId, masked(this.githubClientSecret),
					this.githubUrl, this.githubApiUrl, this.googleClientId, masked(this.googleClientSecret),
					this.sessionDays);
	}

	private static String masked(@Nullable String secret) {
		return (secret != null) ? "******" : "null";
	}

}
