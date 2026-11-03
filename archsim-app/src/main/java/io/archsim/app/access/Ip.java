package io.archsim.app.access;

import java.net.InetAddress;

import org.jspecify.annotations.Nullable;

/**
 * An IP address as Python's address types hold it: the address, and for an IPv6 address
 * the zone it names, if any ({@code fe80::1%eth0}).
 *
 * @param address the address; an IPv4-mapped IPv6 address stays IPv6
 * @param zone the IPv6 zone, or {@code null}
 */
record Ip(InetAddress address, @Nullable String zone) {

}
