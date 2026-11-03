package io.archsim.app.access;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * IP addresses as text, read and written as Python's {@code ipaddress} reads and writes
 * them: an IPv4 address is four decimal numbers without leading zeros, an IPv6 address is
 * written in its shortest form ({@code ::1}, not Java's {@code 0:0:0:0:0:0:0:1}), keeps
 * its zone and stays IPv6 when it maps an IPv4 one ({@code ::ffff:10.0.0.1}), and no host
 * name is ever looked up.
 */
final class IpText {

	private static final String OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";

	private static final Pattern IPV4 = Pattern.compile(OCTET + "(?:\\." + OCTET + "){3}");

	/** What an IPv6 address may hold; the JDK checks the rest. */
	private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

	/** A zone: anything but a slash, as Python's scope ids. */
	private static final Pattern ZONE = Pattern.compile("[^/%]+");

	private static final int IPV6_BYTES = 16;

	private static final int MAPPED_MARK = 10;

	private static final int GROUPS = 8;

	private static final int BITS_PER_BYTE = 8;

	private static final int BYTE = 0xFF;

	private IpText() {
	}

	/**
	 * Reads an address written as one, never a host name.
	 * @param text the address, such as {@code 10.0.0.1}, {@code ::1} or
	 * {@code fe80::1%eth0}
	 * @return the address, or empty when the text is not one as Python reads it
	 */
	static Optional<Ip> parse(String text) {
		if (IPV4.matcher(text).matches()) {
			return Optional.of(new Ip(InetAddress.ofLiteral(text), null));
		}
		int percent = text.indexOf('%');
		String address = (percent != -1) ? text.substring(0, percent) : text;
		String zone = (percent != -1) ? text.substring(percent + 1) : null;
		if (!IPV6.matcher(address).matches() || !isZone(zone)) {
			return Optional.empty();
		}
		try {
			byte[] bytes = sixteenBytes(InetAddress.ofLiteral(address));
			return Optional.of(new Ip(Inet6Address.getByAddress(null, bytes, (NetworkInterface) null), zone));
		}
		catch (IllegalArgumentException | UnknownHostException ex) {
			return Optional.empty();
		}
	}

	private static boolean isZone(@Nullable String zone) {
		return zone == null || ZONE.matcher(zone).matches();
	}

	/**
	 * Writes an address as Python does.
	 * @param ip the address
	 * @return its text: IPv6 compressed as RFC 5952 says, a mapped IPv4 address in dots,
	 * the zone after a {@code %}
	 */
	static String format(Ip ip) {
		if (ip.address() instanceof Inet4Address) {
			return ip.address().getHostAddress();
		}
		byte[] bytes = ip.address().getAddress();
		String text = isMapped(bytes) ? "::ffff:" + dotted(bytes) : compressed(bytes);
		return (ip.zone() != null) ? text + "%" + ip.zone() : text;
	}

	/**
	 * The 16 bytes of an IPv6 address: the JDK reads {@code ::ffff:a.b.c.d} as the IPv4
	 * address it maps, which Python keeps IPv6.
	 * @param address the address the JDK read
	 * @return its IPv6 bytes
	 */
	private static byte[] sixteenBytes(InetAddress address) {
		byte[] bytes = address.getAddress();
		if (bytes.length == IPV6_BYTES) {
			return bytes;
		}
		byte[] mapped = new byte[IPV6_BYTES];
		mapped[MAPPED_MARK] = (byte) BYTE;
		mapped[MAPPED_MARK + 1] = (byte) BYTE;
		System.arraycopy(bytes, 0, mapped, IPV6_BYTES - bytes.length, bytes.length);
		return mapped;
	}

	private static boolean isMapped(byte[] bytes) {
		for (int at = 0; at < MAPPED_MARK; at++) {
			if (bytes[at] != 0) {
				return false;
			}
		}
		return bytes[MAPPED_MARK] == (byte) BYTE && bytes[MAPPED_MARK + 1] == (byte) BYTE;
	}

	private static String dotted(byte[] bytes) {
		StringBuilder text = new StringBuilder();
		for (int at = MAPPED_MARK + 2; at < IPV6_BYTES; at++) {
			text.append((at > MAPPED_MARK + 2) ? "." : "").append(bytes[at] & BYTE);
		}
		return text.toString();
	}

	private static String compressed(byte[] bytes) {
		int[] groups = new int[GROUPS];
		for (int at = 0; at < GROUPS; at++) {
			groups[at] = ((bytes[2 * at] & BYTE) << BITS_PER_BYTE) + (bytes[2 * at + 1] & BYTE);
		}
		int[] zeros = longestZeroRun(groups);
		StringBuilder text = new StringBuilder();
		int at = 0;
		while (at < GROUPS) {
			if (at == zeros[0]) {
				text.append((at == 0) ? "::" : ":");
				at += zeros[1];
			}
			else {
				text.append(Integer.toHexString(groups[at])).append((at < GROUPS - 1) ? ":" : "");
				at++;
			}
		}
		return text.toString();
	}

	/**
	 * The longest run of two or more zero groups, the first when two are as long.
	 * @param groups the address's eight 16-bit groups
	 * @return its start and length, or {@code -1} and {@code 0} when there is none
	 */
	private static int[] longestZeroRun(int[] groups) {
		int bestStart = -1;
		int bestLength = 0;
		int start = -1;
		for (int at = 0; at <= GROUPS; at++) {
			boolean zero = at < GROUPS && groups[at] == 0;
			if (zero && start == -1) {
				start = at;
			}
			if (!zero && start != -1) {
				if (at - start > bestLength && at - start >= 2) {
					bestStart = start;
					bestLength = at - start;
				}
				start = -1;
			}
		}
		return new int[] { bestStart, bestLength };
	}

}
