package io.archsim.app.access;

import java.net.Inet4Address;
import java.util.Arrays;

import org.jspecify.annotations.Nullable;

/**
 * What a limit per address counts an address as: an IPv4 address by itself, also when it
 * comes mapped into IPv6 ({@code ::ffff:10.0.0.1} counts as {@code 10.0.0.1}), and an
 * IPv6 address by its /64, the least a subscriber is given (RFC 6177), so that the
 * addresses of one /64 count once; a larger delegation counts once per /64 (ADR-0106).
 */
final class CountedAddress {

	/** What a request that does not tell its address counts as. */
	private static final String UNKNOWN = "unknown";

	/** The prefix length of the least network an IPv6 subscriber is given. */
	private static final int SUBSCRIBER_PREFIX = 64;

	private static final int BITS_PER_BYTE = 8;

	private CountedAddress() {
	}

	/**
	 * An address as a limit per address counts it.
	 * @param ip the address as the history records it, if the request tells it
	 * @return the IPv4 address; the IPv6 address's /64, such as
	 * {@code 2001:db8:1:2::/64}; text that is no address, as it is; or {@code unknown}
	 */
	static String of(@Nullable String ip) {
		if (ip == null) {
			return UNKNOWN;
		}
		return IpText.parse(ip).map(CountedAddress::counted).orElse(ip);
	}

	private static String counted(Ip ip) {
		if (ip.address() instanceof Inet4Address) {
			return IpText.format(ip);
		}
		// a copy the JDK made for us, so the network may overwrite it
		byte[] bytes = ip.address().getAddress();
		if (IpText.isMapped(bytes)) {
			return IpText.dotted(bytes);
		}
		Arrays.fill(bytes, SUBSCRIBER_PREFIX / BITS_PER_BYTE, bytes.length, (byte) 0);
		return IpText.compressed(bytes) + "/" + SUBSCRIBER_PREFIX;
	}

}
