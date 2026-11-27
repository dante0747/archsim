package io.archsim.app.access;

import org.jspecify.annotations.Nullable;

/**
 * Where a request comes from, for a controller that records it before anyone is signed
 * in: a sign-in's rate limit and the session it starts.
 *
 * @param ip the visitor's address, as {@link Principal#ip()} has it
 * @param userAgent the browser's {@code User-Agent}, if it sent one
 */
public record Visitor(@Nullable String ip, @Nullable String userAgent) {

	/**
	 * The visitor's address as a limit per address counts it: an IPv6 address by its /64,
	 * the least a subscriber is given, so that the addresses of one /64 count once.
	 * @return the IPv4 address or the IPv6 address's /64, or {@code unknown} when the
	 * request does not tell it
	 */
	public String countedAddress() {
		return CountedAddress.of(this.ip);
	}

}
