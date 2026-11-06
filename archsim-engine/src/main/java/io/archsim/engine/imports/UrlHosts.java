package io.archsim.engine.imports;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * The host of a URL-ish value ({@code postgresql://db:5432}, {@code redis://cache},
 * {@code http://api:8080/x}), as Python's {@code urlparse(value).hostname} reads it:
 * without user, password or port, in lower case, and an underscore allowed, as Java's
 * {@code URI} does not allow it.
 */
final class UrlHosts {

	/** The characters a URL's scheme is made of, after its first letter. */
	private static final String SCHEME_CHARACTERS = "+-.";

	private static final Pattern FUTURE_ADDRESS = Pattern.compile("v[a-fA-F0-9]+\\..+");

	private UrlHosts() {
	}

	/**
	 * The host a value names.
	 * @param value the value, as written
	 * @return its host; empty for a value that is not text with {@code ://}, a URL
	 * without a host, or brackets that do not pair
	 */
	static Optional<String> of(JsonNode value) {
		if (!value.isString() || !value.stringValue().contains("://")) {
			return Optional.empty();
		}
		String url = withoutScheme(
				leftStripped(value.stringValue().replace("\t", "").replace("\r", "").replace("\n", "")));
		if (!url.startsWith("//")) {
			return Optional.empty();
		}
		String netloc = netloc(url);
		if (netloc.indexOf('[') >= 0 != netloc.indexOf(']') >= 0) {
			return Optional.empty();
		}
		String hostInfo = netloc.substring(netloc.lastIndexOf('@') + 1);
		int bracket = hostInfo.indexOf('[');
		if (bracket < 0) {
			int colon = hostInfo.indexOf(':');
			String host = (colon >= 0) ? hostInfo.substring(0, colon) : hostInfo;
			return host.isEmpty() ? Optional.empty() : Optional.of(lower(host));
		}
		return bracketed(hostInfo, bracket).map(UrlHosts::lower);
	}

	/**
	 * The host in brackets, when it is an IPv6 address or a future one with nothing
	 * before the bracket and only a port after it, as Python checks it.
	 * @param hostInfo the host and port
	 * @param bracket where the bracket opens
	 * @return the host; empty when the brackets are not an address
	 */
	private static Optional<String> bracketed(String hostInfo, int bracket) {
		String inside = hostInfo.substring(bracket + 1);
		int close = inside.indexOf(']');
		String host = (close >= 0) ? inside.substring(0, close) : inside;
		String after = (close >= 0) ? inside.substring(close + 1) : "";
		if (bracket > 0 || !isPortOrNothing(after) || !isAddress(host)) {
			return Optional.empty();
		}
		return host.isEmpty() ? Optional.empty() : Optional.of(host);
	}

	/**
	 * Whether what follows the brackets is a port, or nothing.
	 * @param after what follows them
	 * @return {@code true} for a port or nothing
	 */
	private static boolean isPortOrNothing(String after) {
		return after.isEmpty() || after.startsWith(":");
	}

	/**
	 * Whether text in brackets is an IPv6 address or a future one ({@code v1.x}).
	 * @param host the text
	 * @return {@code true} for an address
	 */
	private static boolean isAddress(String host) {
		if (host.startsWith("v")) {
			return FUTURE_ADDRESS.matcher(host).matches();
		}
		try {
			return InetAddress.ofLiteral(host) instanceof Inet6Address;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	/**
	 * The URL without the control characters and spaces before it, as Python strips it.
	 * @param url the URL
	 * @return the URL, stripped on its left
	 */
	private static String leftStripped(String url) {
		int start = 0;
		while (start < url.length() && url.charAt(start) <= ' ') {
			start++;
		}
		return url.substring(start);
	}

	/**
	 * The URL after its scheme, when it starts with one.
	 * @param url the URL
	 * @return what follows the scheme's colon; the URL itself without a scheme
	 */
	private static String withoutScheme(String url) {
		int colon = url.indexOf(':');
		if (colon <= 0 || !isAsciiLetter(url.charAt(0))) {
			return url;
		}
		for (int i = 0; i < colon; i++) {
			char character = url.charAt(i);
			if (!isAsciiLetter(character) && !isBetween(character, '0', '9')
					&& SCHEME_CHARACTERS.indexOf(character) < 0) {
				return url;
			}
		}
		return url.substring(colon + 1);
	}

	/**
	 * The network location: what follows {@code //}, up to the path, query or fragment.
	 * @param url the URL after its scheme
	 * @return the network location
	 */
	private static String netloc(String url) {
		int end = url.length();
		for (char delimiter : new char[] { '/', '?', '#' }) {
			int at = url.indexOf(delimiter, 2);
			if (at >= 0) {
				end = Math.min(end, at);
			}
		}
		return url.substring(2, end);
	}

	/**
	 * The host in lower case, a zone after {@code %} kept as written.
	 * @param host the host
	 * @return the host, lowered
	 */
	private static String lower(String host) {
		int percent = host.indexOf('%');
		if (percent < 0) {
			return host.toLowerCase(Locale.ROOT);
		}
		return host.substring(0, percent).toLowerCase(Locale.ROOT) + host.substring(percent);
	}

	private static boolean isAsciiLetter(char character) {
		return isBetween(character, 'a', 'z') || isBetween(character, 'A', 'Z');
	}

	private static boolean isBetween(char character, char first, char last) {
		return character >= first && character <= last;
	}

}
