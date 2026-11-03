package io.archsim.app.access;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * The proxies whose {@code X-ArchSim-Client-IP} header is believed
 * ({@code ARCHSIM_TRUSTED_PROXIES}): addresses, networks such as {@code 10.0.0.0/8}, or
 * host names, which are looked up again once a minute, as the Python API does.
 */
public final class TrustedProxies {

	private static final Duration LOOKUP_LIFE = Duration.ofMinutes(1);

	private final List<Entry> entries;

	private final Clock clock;

	private final Function<String, Set<InetAddress>> lookUp;

	private final Map<String, Lookup> lookups = new ConcurrentHashMap<>();

	/**
	 * Proxies from their settings, whose host names are looked up in DNS.
	 * @param entries addresses, networks or host names; blank ones are left out
	 * @param clock tells when a host name's lookup is a minute old
	 */
	public TrustedProxies(List<String> entries, Clock clock) {
		this(entries, clock, TrustedProxies::addressesOf);
	}

	/**
	 * Proxies from their settings.
	 * @param entries addresses, networks or host names; blank ones are left out
	 * @param clock tells when a host name's lookup is a minute old
	 * @param lookUp a host name's addresses, none when it has none
	 */
	TrustedProxies(List<String> entries, Clock clock, Function<String, Set<InetAddress>> lookUp) {
		this.entries = entries.stream()
			.map(String::strip)
			.filter((entry) -> !entry.isEmpty())
			.map((entry) -> new Entry(entry, Network.parse(entry).orElse(null)))
			.toList();
		this.clock = clock;
		this.lookUp = lookUp;
	}

	/**
	 * Whether a request's peer is one of the proxies: the entry written as the peer is, a
	 * network holding it, or a host name with its address.
	 * @param peer the address the request comes from
	 * @return {@code true} when its header is believed
	 */
	boolean trust(Ip peer) {
		String text = IpText.format(peer);
		return this.entries.stream().anyMatch((entry) -> entry.text().equals(text) || matches(entry, peer.address()));
	}

	private boolean matches(Entry entry, InetAddress peer) {
		Network network = entry.network();
		if (network != null) {
			return network.contains(peer);
		}
		return addressesOfHost(entry.text()).contains(peer);
	}

	private Set<InetAddress> addressesOfHost(String host) {
		Instant now = this.clock.instant();
		return this.lookups.compute(host, (name, known) -> (known != null && known.until().isAfter(now)) ? known
				: new Lookup(this.lookUp.apply(name), now.plus(LOOKUP_LIFE)))
			.addresses();
	}

	private static Set<InetAddress> addressesOf(String host) {
		try {
			return Stream.of(InetAddress.getAllByName(host)).collect(Collectors.toUnmodifiableSet());
		}
		catch (UnknownHostException ex) {
			return Set.of();
		}
	}

	/**
	 * One proxy as the setting gives it, read once.
	 *
	 * @param text the entry, which a peer may equal as written
	 * @param network the address or network it is, or {@code null} for a host name
	 */
	private record Entry(String text, @Nullable Network network) {

	}

	/**
	 * A host name's addresses, until a time.
	 *
	 * @param addresses the addresses it had
	 * @param until when to look it up again
	 */
	private record Lookup(Set<InetAddress> addresses, Instant until) {

	}

}
