package io.archsim.app.signin;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.jspecify.annotations.Nullable;

import io.archsim.app.store.Text;

/**
 * Where a browser lands after signing in, and how a redirect writes it.
 */
final class Landing {

	/** The page a browser lands on when the path it asked for will not do. */
	static final String HOME = "/";

	private static final int MAX_PATH = 500;

	/**
	 * What a redirect leaves unquoted besides letters, digits and {@code _.-~}, as
	 * Starlette's {@code RedirectResponse} does.
	 */
	private static final String UNQUOTED = "_.-~:/%#?=@[]!$&'()*+,;";

	private static final HexFormat HEX = HexFormat.of().withUpperCase();

	private Landing() {
	}

	/**
	 * Where to land after signing in: a path on this site, never another site.
	 * @param next the path sign-in started from, if any
	 * @return the path, cut to 500 characters, or {@code /} when it is missing, names
	 * another site or holds a line break or tab
	 */
	static String safePath(@Nullable String next) {
		if (next == null || !isLocal(next) || hasBreaks(next)) {
			return HOME;
		}
		return Text.cut(next, MAX_PATH);
	}

	/**
	 * Whether a path stays on this site: it starts with one slash, and names no scheme
	 * before its query.
	 * @param next the path
	 * @return {@code true} for a path on this site
	 */
	private static boolean isLocal(String next) {
		if (!next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) {
			return false;
		}
		int query = next.indexOf('?');
		return !((query < 0) ? next : next.substring(0, query)).contains("://");
	}

	private static boolean hasBreaks(String next) {
		return next.chars().anyMatch((character) -> character == '\r' || character == '\n' || character == '\t');
	}

	/**
	 * A redirect's {@code Location}: the URL with every other character percent-encoded
	 * as UTF-8.
	 * @param url the URL or path
	 * @return the header's value
	 */
	static String location(String url) {
		StringBuilder out = new StringBuilder();
		for (byte octet : url.getBytes(StandardCharsets.UTF_8)) {
			char character = (char) octet;
			if (octet >= 0 && (Character.isLetterOrDigit(character) || UNQUOTED.indexOf(character) >= 0)) {
				out.append(character);
			}
			else {
				out.append('%').append(HEX.toHexDigits(octet));
			}
		}
		return out.toString();
	}

}
