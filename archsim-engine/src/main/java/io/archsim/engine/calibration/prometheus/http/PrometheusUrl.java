package io.archsim.engine.calibration.prometheus.http;

import java.math.BigInteger;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.calibration.prometheus.PrometheusException;
import io.archsim.engine.text.PythonStrings;

/**
 * A Prometheus URL split as Python 3.12's {@code urllib.parse.urlsplit} splits it, with
 * the host name and port its result reads; and its host matched against an allowlist as
 * the Python engine's {@code host_allowed} matches it. The allowlist, the address check
 * and the connection all read the host from this one split, so no spelling of a URL can
 * pass one of them and reach another host. What is wrong with a URL is said without
 * repeating it.
 *
 * @param scheme the scheme, in lower case; empty for none
 * @param netloc what follows {@code //}, up to the path, the query or the fragment; empty
 * for none
 * @param path the path, without the query and the fragment
 */
record PrometheusUrl(String scheme, String netloc, String path) {

	private static final int MAX_PORT = 65_535;

	private static final int HTTP_PORT = 80;

	private static final int HTTPS_PORT = 443;

	/** What {@code urlsplit} strips from the start: C0 controls and the space. */
	private static final char LAST_STRIPPED = ' ';

	private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

	private static final String SCHEME_CHARACTERS = LETTERS + "0123456789+-.";

	private static final String BRACKETS = "[]";

	/** The first code unit beyond ASCII. */
	private static final int BEYOND_ASCII = 0x80;

	private static final Pattern IPV_FUTURE = Pattern.compile("v[a-fA-F0-9]+\\..+", Pattern.DOTALL);

	/**
	 * Splits a URL.
	 * @param url the URL
	 * @return its parts
	 * @throws PrometheusException when its brackets are unbalanced or hold no IPv6
	 * address, or its netloc holds characters that turn into a delimiter under NFKC
	 * normalization
	 */
	static PrometheusUrl split(String url) {
		String rest = withoutUnsafeCharacters(url);
		String scheme = "";
		int colon = rest.indexOf(':');
		if (colon > 0 && isScheme(rest.substring(0, colon))) {
			scheme = rest.substring(0, colon).toLowerCase(Locale.ROOT);
			rest = rest.substring(colon + 1);
		}
		String netloc = "";
		if (rest.startsWith("//")) {
			int end = netlocEnd(rest);
			netloc = rest.substring(2, end);
			rest = rest.substring(end);
			checkBrackets(netloc);
		}
		checkNormalization(netloc);
		return new PrometheusUrl(scheme, netloc, before(before(rest, '#'), '?'));
	}

	/**
	 * Whether a URL's host is on an allowlist.
	 * @param url the URL
	 * @param allowed the hosts, {@code host} or {@code host:port} (IPv6 as
	 * {@code [::1]:9090}); none allows any host
	 * @return whether its host is allowed
	 * @throws PrometheusException when the URL's brackets or port are not valid
	 */
	static boolean allowed(String url, List<String> allowed) {
		if (allowed.isEmpty()) {
			return true;
		}
		PrometheusUrl parts = split(url);
		String host = Objects.requireNonNullElse(parts.hostname(), "").toLowerCase(Locale.ROOT);
		int port = parts.portOrDefault();
		return allowed.stream().anyMatch((entry) -> matches(entry, host, port));
	}

	/**
	 * The host name, in lower case up to a zone.
	 * @return the name; {@code null} for none
	 */
	@Nullable String hostname() {
		String hostname = hostAndPort()[0];
		if (hostname.isEmpty()) {
			return null;
		}
		int percent = hostname.indexOf('%');
		return (percent < 0) ? hostname.toLowerCase(Locale.ROOT)
				: hostname.substring(0, percent).toLowerCase(Locale.ROOT) + hostname.substring(percent);
	}

	/**
	 * The port written.
	 * @return the port; {@code null} for none
	 * @throws PrometheusException when it is not a number from 0 to 65535
	 */
	@Nullable Integer port() {
		String port = hostAndPort()[1];
		if (port.isEmpty()) {
			return null;
		}
		if (!port.chars().allMatch((character) -> character >= '0' && character <= '9')
				|| new BigInteger(port).compareTo(BigInteger.valueOf(MAX_PORT)) > 0) {
			throw new PrometheusException("the Prometheus URL has an invalid port");
		}
		return Integer.parseInt(port);
	}

	/**
	 * The port connected to: the one written, else the scheme's (also for port 0, as
	 * Python reads it).
	 * @return the port
	 * @throws PrometheusException when the port written is not one
	 */
	int portOrDefault() {
		Integer port = port();
		if (port != null && port != 0) {
			return port;
		}
		return "https".equals(this.scheme) ? HTTPS_PORT : HTTP_PORT;
	}

	/**
	 * Whether the scheme is {@code http} or {@code https}.
	 * @return whether it is
	 */
	boolean isHttp() {
		return "http".equals(this.scheme) || "https".equals(this.scheme);
	}

	/**
	 * Python's {@code _hostinfo}: the host and the port after the last {@code @}, a host
	 * in brackets read up to {@code ]}.
	 * @return the host and the port, each empty for none
	 */
	private String[] hostAndPort() {
		String info = this.netloc.substring(this.netloc.lastIndexOf('@') + 1);
		int open = info.indexOf('[');
		if (open < 0) {
			return new String[] { before(info, ':'), after(info, ':') };
		}
		String bracketed = info.substring(open + 1);
		return new String[] { before(bracketed, ']'), after(after(bracketed, ']'), ':') };
	}

	/**
	 * Whether an allowlist entry matches a host and port, as {@code host_allowed} reads
	 * it: a port after the last colon, unless it is not a number or the colon is inside a
	 * bare IPv6 address.
	 */
	private static boolean matches(String entry, String host, int port) {
		String allowed = PythonStrings.strip(entry).toLowerCase(Locale.ROOT);
		int colon = allowed.lastIndexOf(':');
		String name = allowed;
		String entryPort = "";
		if (colon >= 0) {
			String before = allowed.substring(0, colon);
			String digits = allowed.substring(colon + 1);
			boolean bareIpv6 = before.indexOf(':') >= 0 && !before.endsWith("]");
			if (!digits.isEmpty() && digits.chars().allMatch((digit) -> digit >= '0' && digit <= '9') && !bareIpv6) {
				name = before;
				entryPort = digits;
			}
		}
		return stripBrackets(name).equals(host)
				&& (entryPort.isEmpty() || new BigInteger(entryPort).equals(BigInteger.valueOf(port)));
	}

	/**
	 * What {@code urlsplit} reads: the URL without the C0 controls and spaces it starts
	 * with, and without any tab, carriage return or line feed.
	 */
	private static String withoutUnsafeCharacters(String url) {
		int start = 0;
		while (start < url.length() && url.charAt(start) <= LAST_STRIPPED) {
			start++;
		}
		return url.substring(start).replace("\t", "").replace("\r", "").replace("\n", "");
	}

	private static boolean isScheme(String text) {
		boolean letter = LETTERS.indexOf(text.charAt(0)) >= 0;
		return letter && text.chars().allMatch((character) -> SCHEME_CHARACTERS.indexOf(character) >= 0);
	}

	/**
	 * Where the netloc after {@code //} ends: at the first {@code /}, {@code ?} or
	 * {@code #}.
	 */
	private static int netlocEnd(String rest) {
		int end = rest.length();
		for (char delimiter : new char[] { '/', '?', '#' }) {
			int at = rest.indexOf(delimiter, 2);
			if (at >= 0) {
				end = Math.min(end, at);
			}
		}
		return end;
	}

	/**
	 * Python's bracket checks ({@code _check_bracketed_netloc}): brackets in pairs, and
	 * after the last {@code @} nothing before {@code [}, nothing between {@code ]} and
	 * the port's colon, and an IPv6 address (or an IPvFuture one) between them.
	 */
	private static void checkBrackets(String netloc) {
		boolean open = netloc.indexOf('[') >= 0;
		boolean close = netloc.indexOf(']') >= 0;
		if (open != close) {
			throw new PrometheusException("Invalid IPv6 URL");
		}
		if (!open) {
			return;
		}
		String info = netloc.substring(netloc.lastIndexOf('@') + 1);
		int bracket = info.indexOf('[');
		String host;
		if (bracket >= 0) {
			String port = after(info.substring(bracket + 1), ']');
			boolean textAfterBracket = !port.isEmpty() && !port.startsWith(":");
			if (bracket > 0 || textAfterBracket) {
				throw new PrometheusException("Invalid IPv6 URL");
			}
			host = before(info.substring(bracket + 1), ']');
		}
		else {
			host = before(info, ':');
		}
		checkBracketedHost(host);
	}

	/**
	 * Python's {@code _check_bracketed_host}. Python repeats a host that is no address;
	 * this says only that the URL's brackets are wrong.
	 */
	private static void checkBracketedHost(String host) {
		if (host.startsWith("v")) {
			if (!IPV_FUTURE.matcher(host).matches()) {
				throw new PrometheusException("IPvFuture address is invalid");
			}
		}
		else if (IpLiterals.isIpv4(host)) {
			throw new PrometheusException("An IPv4 address cannot be in brackets");
		}
		else if (IpLiterals.parse(host) == null) {
			throw new PrometheusException("Invalid IPv6 URL");
		}
	}

	/**
	 * Python's {@code _checknetloc}: a netloc beyond ASCII must not hold characters that
	 * IDNA's NFKC normalization turns into a delimiter, such as a full-width solidus.
	 * Python repeats the netloc; this does not.
	 */
	private static void checkNormalization(String netloc) {
		if (netloc.chars().allMatch((character) -> character < BEYOND_ASCII)) {
			return;
		}
		String plain = netloc.replace("@", "").replace(":", "").replace("#", "").replace("?", "");
		String normalized = Normalizer.normalize(plain, Normalizer.Form.NFKC);
		if (!normalized.equals(plain) && normalized.chars().anyMatch((character) -> "/?#@:".indexOf(character) >= 0)) {
			throw new PrometheusException("netloc contains invalid characters under NFKC normalization");
		}
	}

	/** Python's {@code text.partition(separator)[0]}. */
	private static String before(String text, char separator) {
		int at = text.indexOf(separator);
		return (at < 0) ? text : text.substring(0, at);
	}

	/** Python's {@code text.partition(separator)[2]}. */
	private static String after(String text, char separator) {
		int at = text.indexOf(separator);
		return (at < 0) ? "" : text.substring(at + 1);
	}

	/** Python's {@code text.strip("[]")}. */
	private static String stripBrackets(String text) {
		int start = 0;
		int end = text.length();
		while (start < end && BRACKETS.indexOf(text.charAt(start)) >= 0) {
			start++;
		}
		while (end > start && BRACKETS.indexOf(text.charAt(end - 1)) >= 0) {
			end--;
		}
		return text.substring(start, end);
	}

}
