package io.archsim.app.access;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The address a request comes from: {@code X-ArchSim-Client-IP} when a trusted proxy (the
 * web server) sends a valid one, else the peer's.
 */
class ClientIps {

	/** The visitor's address, as the web server saw it. */
	static final String HEADER = "X-ArchSim-Client-IP";

	private final TrustedProxies proxies;

	ClientIps(TrustedProxies proxies) {
		this.proxies = proxies;
	}

	/**
	 * The address a request comes from, as the history records it.
	 * @param request the request
	 * @return the forwarded address when a trusted proxy sends a valid one, else the
	 * peer's
	 */
	Optional<String> of(HttpServletRequest request) {
		Optional<Ip> peer = IpText.parse(request.getRemoteAddr());
		String forwarded = request.getHeader(HEADER);
		if (forwarded != null && peer.isPresent() && this.proxies.trust(peer.get())) {
			Optional<Ip> client = IpText.parse(forwarded.strip());
			if (client.isPresent()) {
				return client.map(IpText::format);
			}
		}
		return peer.map(IpText::format);
	}

}
