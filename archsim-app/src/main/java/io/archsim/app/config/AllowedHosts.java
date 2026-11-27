package io.archsim.app.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The host names a server without sign-in answers to besides this machine's own
 * ({@code ARCHSIM_ALLOWED_HOSTS}), compared without case: the name another container
 * calls it by, such as compose's {@code api}. A request addressed to any other name is
 * refused, so a page that points its own name at this machine reaches nothing.
 *
 * @param names the names, lower case, none blank
 */
public record AllowedHosts(List<String> names) {

	/** This machine's names, as a request's host has them, which are always allowed. */
	private static final Set<String> LOOPBACK = Set.of("localhost", "[::1]");

	/**
	 * An address of the loopback network, 127.0.0.0/8, in dotted form, so that
	 * {@code 127.evil.example} is none.
	 */
	private static final Pattern LOOPBACK_ADDRESS = Pattern.compile("127(\\.\\d{1,3}){3}");

	/**
	 * The names, stripped, in lower case, without blanks.
	 * @param names the names
	 */
	public AllowedHosts {
		names = names.stream()
			.map((name) -> name.strip().toLowerCase(Locale.ROOT))
			.filter((name) -> !name.isEmpty())
			.toList();
	}

	/**
	 * Reads the setting. Spring Boot's binder finds this method by its name.
	 * @param setting a JSON array of names, or names separated by commas
	 * @return the names
	 * @throws IllegalArgumentException when the setting starts as a JSON array but is not
	 * one of strings
	 */
	public static AllowedHosts valueOf(String setting) {
		return new AllowedHosts(SettingLists.parse("ARCHSIM_ALLOWED_HOSTS", setting));
	}

	/**
	 * Whether a request addressed to a host name is this server's to answer.
	 * @param host the name the request is addressed to, without its port
	 * @return {@code true} for this machine's names and the ones the setting lists
	 */
	public boolean allows(String host) {
		String name = host.toLowerCase(Locale.ROOT);
		return LOOPBACK.contains(name) || LOOPBACK_ADDRESS.matcher(name).matches() || this.names.contains(name);
	}

}
