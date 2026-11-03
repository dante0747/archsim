package io.archsim.app.config;

import java.util.List;
import java.util.Locale;

/**
 * The verified emails whose first sign-in makes them an owner of the default workspace
 * ({@code ARCHSIM_ADMIN_EMAILS}), compared without case.
 *
 * @param emails the emails, lower case
 */
public record AdminEmails(List<String> emails) {

	/**
	 * The emails, copied, stripped and in lower case.
	 * @param emails the emails
	 */
	public AdminEmails {
		emails = emails.stream().map((email) -> email.strip().toLowerCase(Locale.ROOT)).toList();
	}

	/**
	 * Reads the setting. Spring Boot's binder finds this method by its name.
	 * @param setting a JSON array of emails, or emails separated by commas
	 * @return the emails
	 * @throws IllegalArgumentException when the setting starts as a JSON array but is not
	 * one of strings
	 */
	public static AdminEmails valueOf(String setting) {
		return new AdminEmails(SettingLists.parse("ARCHSIM_ADMIN_EMAILS", setting));
	}

	/**
	 * Whether an email is an administrator's.
	 * @param email a verified email, lower case
	 * @return {@code true} when the setting lists it
	 */
	public boolean includes(String email) {
		return this.emails.contains(email);
	}

}
