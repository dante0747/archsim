package io.archsim.app.signin;

import java.util.List;
import java.util.Locale;
import java.util.stream.StreamSupport;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.app.store.Text;

/**
 * Who signed in, as the provider says. Nothing else the provider returns is kept.
 *
 * @param subject the provider's id of the account
 * @param name the name to show, at most 200 characters
 * @param email the account's email in lower case, if any
 * @param emailVerified whether the provider verified the email: an unverified one is
 * never trusted
 * @param avatarUrl the account's picture, at most 500 characters
 * @param login the GitHub login, if signing in with GitHub
 */
record ProviderProfile(String subject, String name, @Nullable String email, boolean emailVerified,
		@Nullable String avatarUrl, @Nullable String login) {

	private static final int MAX_NAME = 200;

	private static final int MAX_URL = 500;

	/**
	 * A GitHub account, from {@code /user} and {@code /user/emails}: the primary verified
	 * email, else the first verified one.
	 * @param user what {@code /user} answered
	 * @param emails what {@code /user/emails} answered (empty when GitHub refused)
	 * @return the profile
	 * @throws SignInFailure when the user has no id
	 */
	static ProviderProfile github(JsonNode user, JsonNode emails) {
		List<JsonNode> verified = StreamSupport.stream(emails.spliterator(), false)
			.filter((email) -> email.isObject() && truthy(email.path("verified")) && truthy(email.path("email")))
			.toList();
		JsonNode chosen = verified.stream()
			.filter((email) -> truthy(email.path("primary")))
			.findFirst()
			.orElse(verified.isEmpty() ? null : verified.getFirst());
		String login = textOr(user.path("login"), "");
		String name = textOr(user.path("name"), login.isEmpty() ? "GitHub user" : login);
		String email = (chosen != null) ? text(chosen.path("email")).toLowerCase(Locale.ROOT) : null;
		return new ProviderProfile(subject(user.path("id")), Text.cut(name, MAX_NAME), email, chosen != null,
				optional(user.path("avatar_url"), MAX_URL), login.isEmpty() ? null : Text.cut(login, MAX_NAME));
	}

	/**
	 * A Google account, from OpenID Connect's {@code userinfo}.
	 * @param info what {@code userinfo} answered
	 * @return the profile
	 * @throws SignInFailure when the account has no subject
	 */
	static ProviderProfile google(JsonNode info) {
		JsonNode email = info.path("email");
		boolean hasEmail = truthy(email);
		String name = textOr(info.path("name"), textOr(email, "Google user"));
		return new ProviderProfile(subject(info.path("sub")), Text.cut(name, MAX_NAME),
				hasEmail ? text(email).toLowerCase(Locale.ROOT) : null, truthy(info.path("email_verified")) && hasEmail,
				optional(info.path("picture"), MAX_URL), null);
	}

	private static String subject(JsonNode id) {
		if (id.isMissingNode() || id.isNull()) {
			throw SignInFailure.provider("the provider named no account");
		}
		return text(id);
	}

	private static @Nullable String optional(JsonNode value, int maxLength) {
		return truthy(value) ? Text.cut(text(value), maxLength) : null;
	}

	/**
	 * Whether a JSON value counts as present, as Python's truth does: not null, false,
	 * zero or empty.
	 * @param value the value
	 * @return {@code true} when it counts
	 */
	private static boolean truthy(JsonNode value) {
		if (value.isMissingNode() || value.isNull()) {
			return false;
		}
		if (value.isBoolean()) {
			return value.booleanValue();
		}
		if (value.isNumber()) {
			return value.doubleValue() != 0;
		}
		return value.isString() ? !value.stringValue().isEmpty() : !value.isEmpty();
	}

	private static String textOr(JsonNode value, String fallback) {
		return truthy(value) ? text(value) : fallback;
	}

	private static String text(JsonNode value) {
		return value.isString() ? value.stringValue() : value.toString();
	}

}
