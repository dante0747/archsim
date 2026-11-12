package io.archsim.app.config;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * Where the database is, from {@code ARCHSIM_DATABASE_URL}: a {@code postgresql://} or
 * {@code postgres://} URL
 * ({@code postgresql://user:password@host:5432/archsim?sslmode=require}), the same with a
 * SQLAlchemy driver name as the Python API took it ({@code postgresql+psycopg://}), or a
 * JDBC URL, which is used as it is. The query goes to the JDBC driver as its connection
 * properties, by the driver's names. The URL holds a password, so no message and no
 * {@link #toString()} repeats it.
 *
 * @param jdbcUrl the JDBC URL of the database
 * @param username the user to connect as, when the URL names one
 * @param password the user's password, when the URL gives one
 */
public record DatabaseUrl(String jdbcUrl, @Nullable String username, @Nullable String password) {

	private static final int DEFAULT_PORT = 5432;

	/**
	 * The parts of a URL, by SQLAlchemy's own pattern
	 * ({@code sqlalchemy.engine.url._parse_url}). It reads a {@code postgresql://} URL
	 * too, and takes several hosts with one port ({@code primary,standby:6543}); a port
	 * for each host is not a number.
	 */
	private static final Pattern URL_PARTS = Pattern.compile("""
			(?<name>[\\w+]+)://\
			(?:(?<username>[^:/]*)(?::(?<password>[^@]*))?@)?\
			(?:(?:\\[(?<ipv6host>[^/?]+)]|(?<ipv4host>[^/:?]+))?(?::(?<port>[^/?]*))?)?\
			(?:/(?<database>[^?]*))?\
			(?:\\?(?<query>.*))?""", Pattern.DOTALL);

	/** The schemes of a PostgreSQL URL, with or without a driver. */
	private static final Pattern POSTGRESQL = Pattern.compile("postgres(?:ql)?(?:\\+\\w+)?");

	/** A password in a JDBC URL's parameters. */
	private static final Pattern PASSWORD_PARAMETER = Pattern.compile("(?i)(password=)[^&]*");

	/** The length of a percent escape, such as {@code %40}. */
	private static final int ESCAPE_LENGTH = 3;

	/** What a usable setting looks like, for the messages. */
	private static final String EXAMPLE = "such as postgresql://user:password@host:5432/archsim";

	/**
	 * Reads a database URL.
	 * @param url a PostgreSQL URL, with or without a SQLAlchemy driver name, or a JDBC
	 * URL
	 * @return the JDBC URL and the credentials it names
	 * @throws IllegalArgumentException when the URL names no PostgreSQL database; the
	 * message never repeats the URL
	 */
	public static DatabaseUrl parse(String url) {
		String text = url.strip();
		if (text.startsWith("jdbc:")) {
			return new DatabaseUrl(text, null, null);
		}
		Matcher parts = URL_PARTS.matcher(text);
		if (!parts.matches() || !POSTGRESQL.matcher(parts.group("name")).matches()) {
			throw new IllegalArgumentException("ARCHSIM_DATABASE_URL must name a PostgreSQL database, " + EXAMPLE);
		}
		String jdbcUrl = "jdbc:postgresql://" + addresses(parts) + "/" + nonNull(parts.group("database"))
				+ ((parts.group("query") != null) ? "?" + parts.group("query") : "");
		return new DatabaseUrl(jdbcUrl, decode(parts.group("username")), decode(parts.group("password")));
	}

	/**
	 * A database URL fit for a log: its password, if any, replaced by {@code ***}.
	 * @param url a PostgreSQL or JDBC URL
	 * @return the URL without its password
	 */
	public static String masked(String url) {
		String withoutUserPassword = url.replaceFirst("(://[^:/@]*):[^@]*@", "$1:***@");
		return PASSWORD_PARAMETER.matcher(withoutUserPassword).replaceAll("$1***");
	}

	@Override
	public String toString() {
		return "DatabaseUrl[jdbcUrl=" + masked(this.jdbcUrl) + ", username=" + this.username + ", password="
				+ ((this.password != null) ? "***" : null) + "]";
	}

	/**
	 * The addresses of a URL's hosts, as the JDBC driver takes them: each host with the
	 * URL's one port, as libpq gave every host the port SQLAlchemy read. A Unix socket is
	 * refused, whether a host is left out or names a socket directory percent-encoded as
	 * libpq takes it: the driver connects over TCP. So is a list of IPv6 hosts in
	 * brackets, which would keep the port for the last one only.
	 */
	private static String addresses(Matcher parts) {
		int port = port(parts.group("port"));
		String ipv6 = parts.group("ipv6host");
		if (ipv6 != null) {
			if (ipv6.contains("]")) {
				throw noHost();
			}
			return '[' + ipv6 + ']' + ":" + port;
		}
		String names = parts.group("ipv4host");
		if (names == null || names.contains("%")) {
			throw noHost();
		}
		List<String> hosts = List.of(names.split(",", -1));
		if (hosts.contains("")) {
			throw noHost();
		}
		return hosts.stream().map((host) -> host + ":" + port).collect(Collectors.joining(","));
	}

	private static IllegalArgumentException noHost() {
		return new IllegalArgumentException("ARCHSIM_DATABASE_URL must name the database's host, " + EXAMPLE);
	}

	private static int port(@Nullable String port) {
		if (port == null || port.isEmpty()) {
			return DEFAULT_PORT;
		}
		try {
			return Integer.parseInt(port);
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException("ARCHSIM_DATABASE_URL's port must be a number, " + EXAMPLE);
		}
	}

	private static String nonNull(@Nullable String text) {
		return (text != null) ? text : "";
	}

	/**
	 * Percent-decoding as SQLAlchemy does it for the user and password (Python's
	 * {@code unquote}): {@code +} stays a plus, and a {@code %} that starts no escape
	 * stays as it is, so a password never fails in a way that would show it.
	 */
	private static @Nullable String decode(@Nullable String text) {
		if (text == null) {
			return null;
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		int at = 0;
		while (at < text.length()) {
			if (isEscape(text, at)) {
				bytes.write(HexFormat.fromHexDigits(text, at + 1, at + ESCAPE_LENGTH));
				at += ESCAPE_LENGTH;
			}
			else {
				int codePoint = text.codePointAt(at);
				bytes.writeBytes(Character.toString(codePoint).getBytes(StandardCharsets.UTF_8));
				at += Character.charCount(codePoint);
			}
		}
		return bytes.toString(StandardCharsets.UTF_8);
	}

	private static boolean isEscape(String text, int at) {
		return text.charAt(at) == '%' && at + ESCAPE_LENGTH <= text.length()
				&& HexFormat.isHexDigit(text.charAt(at + 1)) && HexFormat.isHexDigit(text.charAt(at + 2));
	}

}
