package io.archsim.app.access;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * An address or a network of them, written as Python's {@code ip_network(entry,
 * strict=False)} reads it: one address, {@code 10.0.0.0/8}, {@code 2001:db8::/32}, or an
 * IPv4 network with a netmask ({@code 10.0.0.0/255.0.0.0}) or a hostmask
 * ({@code 10.0.0.0/0.255.255.255}).
 *
 * @param base the network's address
 * @param prefixLength how many leading bits a member shares with it
 */
record Network(InetAddress base, int prefixLength) {

	private static final Pattern DIGITS = Pattern.compile("\\d{1,3}");

	private static final int BITS_PER_BYTE = 8;

	private static final int BYTE = 0xFF;

	private static final int IPV4_BITS = 32;

	private static final long ALL_ONES = 0xFFFF_FFFFL;

	/**
	 * Reads a network.
	 * @param text an address, or an address and a prefix length or mask after a slash
	 * @return the network, or empty when the text is neither (a host name)
	 */
	static Optional<Network> parse(String text) {
		int slash = text.indexOf('/');
		Optional<Ip> base = IpText.parse((slash != -1) ? text.substring(0, slash) : text);
		if (base.isEmpty() || base.get().zone() != null) {
			return Optional.empty();
		}
		InetAddress address = base.get().address();
		int bits = address.getAddress().length * BITS_PER_BYTE;
		if (slash == -1) {
			return Optional.of(new Network(address, bits));
		}
		OptionalInt length = prefixLength(text.substring(slash + 1), bits);
		return length.isPresent() ? Optional.of(new Network(address, length.getAsInt())) : Optional.empty();
	}

	/**
	 * Whether an address is in this network. An IPv4 address is never in an IPv6 network,
	 * mapped or not, as in Python.
	 * @param address the address
	 * @return {@code true} when its leading bits are the network's
	 */
	boolean contains(InetAddress address) {
		byte[] network = this.base.getAddress();
		byte[] candidate = address.getAddress();
		if (network.length != candidate.length) {
			return false;
		}
		int whole = this.prefixLength / BITS_PER_BYTE;
		for (int at = 0; at < whole; at++) {
			if (network[at] != candidate[at]) {
				return false;
			}
		}
		int rest = this.prefixLength % BITS_PER_BYTE;
		if (rest == 0) {
			return true;
		}
		int mask = (BYTE << (BITS_PER_BYTE - rest)) & BYTE;
		return (network[whole] & mask) == (candidate[whole] & mask);
	}

	/**
	 * The prefix length after the slash: decimal digits, or for IPv4 a netmask or a
	 * hostmask.
	 * @param text what follows the slash
	 * @param bits the address's length in bits
	 * @return the prefix length, or empty when the text is none of these
	 */
	private static OptionalInt prefixLength(String text, int bits) {
		if (DIGITS.matcher(text).matches()) {
			int length = Integer.parseInt(text);
			return (length <= bits) ? OptionalInt.of(length) : OptionalInt.empty();
		}
		Optional<Ip> mask = IpText.parse(text);
		if (bits != IPV4_BITS || mask.isEmpty() || !(mask.get().address() instanceof Inet4Address)) {
			return OptionalInt.empty();
		}
		long value = Integer.toUnsignedLong(ByteBuffer.wrap(mask.get().address().getAddress()).getInt());
		int ones = Long.bitCount(value);
		if (value == ((ALL_ONES << (IPV4_BITS - ones)) & ALL_ONES)) {
			// a netmask: ones, then zeros
			return OptionalInt.of(ones);
		}
		if (value == (1L << ones) - 1) {
			// a hostmask: zeros, then ones
			return OptionalInt.of(IPV4_BITS - ones);
		}
		return OptionalInt.empty();
	}

}
