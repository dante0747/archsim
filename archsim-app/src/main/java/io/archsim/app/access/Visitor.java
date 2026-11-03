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
	 * The visitor's address, as a limit per address counts it.
	 * @return the address, or {@code unknown} when the request does not tell it
	 */
	public String ipOrUnknown() {
		return (this.ip != null) ? this.ip : "unknown";
	}

}
