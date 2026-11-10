package io.archsim.engine.calibration.prometheus.http;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * IP addresses written out, read as Python 3.12's {@code ipaddress.ip_address} reads
 * them: IPv4 as four decimal octets without leading zeros; IPv6 as eight hextets, or
 * fewer with one {@code ::}, an IPv4 address in the last two, and a zone after {@code %}.
 * Nothing is looked up.
 */
final class IpLiterals {

	private static final int IPV4_OCTETS = 4;

	private static final int MAX_OCTET = 255;

	private static final int MAX_OCTET_DIGITS = 3;

	private static final int HEXTETS = 8;

	private static final int MAX_HEXTET_DIGITS = 4;

	private static final int HEXTET_BITS = 16;

	private static final int OCTET_BITS = 8;

	private static final int HEXTET_MASK = 0xFFFF;

	/** The fewest parts between colons: {@code ::} splits into three. */
	private static final int MIN_PARTS = 3;

	private static final String HEX_DIGITS = "0123456789abcdefABCDEF";

	private IpLiterals() {
	}

	/**
	 * An address written out.
	 * @param text the text
	 * @return the address as a number; {@code null} when it is not an IPv4 or IPv6
	 * address
	 */
	static @Nullable BigInteger parse(String text) {
		Long ipv4 = ipv4(text);
		return (ipv4 != null) ? BigInteger.valueOf(ipv4) : ipv6(text);
	}

	/**
	 * Whether a text is an IPv4 address written out.
	 * @param text the text
	 * @return whether it is one
	 */
	static boolean isIpv4(String text) {
		return ipv4(text) != null;
	}

	private static @Nullable Long ipv4(String text) {
		String[] octets = text.split("\\.", -1);
		if (octets.length != IPV4_OCTETS) {
			return null;
		}
		long value = 0;
		for (String octet : octets) {
			int parsed = octet(octet);
			if (parsed < 0) {
				return null;
			}
			value = (value << OCTET_BITS) | parsed;
		}
		return value;
	}

	/**
	 * Python's {@code _parse_octet}.
	 * @param text the octet
	 * @return its value; {@code -1} for one Python refuses
	 */
	private static int octet(String text) {
		if (text.isEmpty() || text.length() > MAX_OCTET_DIGITS || !text.chars().allMatch(IpLiterals::isAsciiDigit)) {
			return -1;
		}
		int value = Integer.parseInt(text);
		boolean leadingZero = text.length() > 1 && text.charAt(0) == '0';
		return (value > MAX_OCTET || leadingZero) ? -1 : value;
	}

	private static @Nullable BigInteger ipv6(String text) {
		if (text.indexOf('/') >= 0) {
			return null;
		}
		String address = withoutZone(text);
		if (address == null || address.isEmpty()) {
			return null;
		}
		List<String> parts = parts(address);
		if (parts == null || parts.size() > HEXTETS + 1) {
			return null;
		}
		Layout layout = Layout.of(parts);
		return (layout != null) ? layout.value(parts) : null;
	}

	/**
	 * Python's {@code _split_scope_id}: the address before a {@code %}, which must be
	 * followed by a zone without another.
	 * @param text the text
	 * @return the address; {@code null} for an empty or doubled zone
	 */
	private static @Nullable String withoutZone(String text) {
		int percent = text.indexOf('%');
		if (percent < 0) {
			return text;
		}
		String zone = text.substring(percent + 1);
		return (zone.isEmpty() || zone.indexOf('%') >= 0) ? null : text.substring(0, percent);
	}

	/**
	 * The parts between colons, an IPv4 address at the end written as two hextets.
	 * @param address the address
	 * @return the parts; {@code null} for too few, or an IPv4 end that is not one
	 */
	private static @Nullable List<String> parts(String address) {
		List<String> parts = new ArrayList<>(Arrays.asList(address.split(":", -1)));
		if (parts.size() < MIN_PARTS) {
			return null;
		}
		if (parts.getLast().indexOf('.') >= 0) {
			Long ipv4 = ipv4(parts.removeLast());
			if (ipv4 == null) {
				return null;
			}
			parts.add(Long.toHexString((ipv4 >> HEXTET_BITS) & HEXTET_MASK));
			parts.add(Long.toHexString(ipv4 & HEXTET_MASK));
		}
		return parts;
	}

	private static boolean isAsciiDigit(int character) {
		return character >= '0' && character <= '9';
	}

	/**
	 * Python's {@code _parse_hextet}.
	 * @param text the hextet
	 * @return its value; {@code -1} for one Python refuses
	 */
	private static int hextet(String text) {
		if (text.isEmpty() || text.length() > MAX_HEXTET_DIGITS
				|| !text.chars().allMatch((character) -> HEX_DIGITS.indexOf(character) >= 0)) {
			return -1;
		}
		return Integer.parseInt(text, HEXTET_BITS);
	}

	/**
	 * Where the hextets written go: the first {@code high} at the top, the last
	 * {@code low} at the bottom, and {@code skipped} zero hextets between, for a
	 * {@code ::}.
	 *
	 * @param high the hextets written before {@code ::}, or all of them
	 * @param low the hextets written after {@code ::}
	 * @param skipped the zero hextets {@code ::} stands for
	 */
	private record Layout(int high, int low, int skipped) {

		/**
		 * Python's checks of where {@code ::} is, and how much it may stand for.
		 * @param parts the parts between colons
		 * @return the layout; {@code null} for one Python refuses
		 */
		static @Nullable Layout of(List<String> parts) {
			int skip = -1;
			for (int i = 1; i < parts.size() - 1; i++) {
				if (parts.get(i).isEmpty()) {
					if (skip >= 0) {
						return null;
					}
					skip = i;
				}
			}
			if (skip < 0) {
				boolean whole = parts.size() == HEXTETS && !parts.getFirst().isEmpty() && !parts.getLast().isEmpty();
				return whole ? new Layout(parts.size(), 0, 0) : null;
			}
			return skipping(parts, skip);
		}

		private static @Nullable Layout skipping(List<String> parts, int skip) {
			// a colon at either end is only allowed as half of the "::"
			int high = parts.getFirst().isEmpty() ? skip - 1 : skip;
			int low = parts.getLast().isEmpty() ? parts.size() - skip - 2 : parts.size() - skip - 1;
			boolean lonelyLeadingColon = parts.getFirst().isEmpty() && high != 0;
			boolean lonelyTrailingColon = parts.getLast().isEmpty() && low != 0;
			if (lonelyLeadingColon || lonelyTrailingColon) {
				return null;
			}
			int skipped = HEXTETS - (high + low);
			return (skipped < 1) ? null : new Layout(high, low, skipped);
		}

		@Nullable BigInteger value(List<String> parts) {
			BigInteger value = BigInteger.ZERO;
			List<String> written = new ArrayList<>(parts.subList(0, this.high));
			written.addAll(parts.subList(parts.size() - this.low, parts.size()));
			for (int i = 0; i < written.size(); i++) {
				int hextet = hextet(written.get(i));
				if (hextet < 0) {
					return null;
				}
				int shift = (i == this.high) ? HEXTET_BITS * (this.skipped + 1) : HEXTET_BITS;
				value = value.shiftLeft(shift).or(BigInteger.valueOf(hextet));
			}
			return (written.size() == this.high) ? value.shiftLeft(HEXTET_BITS * this.skipped) : value;
		}

	}

}
