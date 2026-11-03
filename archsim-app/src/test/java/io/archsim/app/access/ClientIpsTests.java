package io.archsim.app.access;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a request comes from: a trusted proxy's {@code X-ArchSim-Client-IP}, else the
 * peer's address.
 */
class ClientIpsTests {

	private final ClientIps clientIps = new ClientIps(new TrustedProxies(List.of("127.0.0.1"), Clock.systemUTC()));

	@Test
	void of_trustedProxyForwarding_isTheVisitorsAddress() {
		MockHttpServletRequest request = request("127.0.0.1", "203.0.113.9");

		assertThat(this.clientIps.of(request)).contains("203.0.113.9");
	}

	@Test
	void of_untrustedPeerForwarding_isThePeersAddress() {
		MockHttpServletRequest request = request("198.51.100.7", "203.0.113.9");

		assertThat(this.clientIps.of(request)).contains("198.51.100.7");
	}

	@Test
	void of_forwardedValueThatIsNoAddress_isThePeersAddress() {
		MockHttpServletRequest request = request("127.0.0.1", "not-an-address");

		assertThat(this.clientIps.of(request)).contains("127.0.0.1");
	}

	static MockHttpServletRequest request(String peer, String forwarded) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(peer);
		request.addHeader(ClientIps.HEADER, forwarded);
		return request;
	}

}
