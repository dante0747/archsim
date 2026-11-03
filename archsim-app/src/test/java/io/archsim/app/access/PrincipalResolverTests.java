package io.archsim.app.access;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import io.archsim.app.config.TestProperties;
import io.archsim.app.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

/**
 * Who is asking without sign-in, and from where: a trusted proxy's
 * {@code X-ArchSim-Client-IP}, else the peer's address.
 */
class PrincipalResolverTests {

	private static final MethodParameter PARAMETER = mock();

	private final PrincipalResolver resolver = new PrincipalResolver(TestProperties.with(),
			new TrustedProxies(List.of("127.0.0.1"), Clock.systemUTC()));

	@Test
	void clientIp_trustedProxyForwarding_isTheVisitorsAddress() {
		MockHttpServletRequest request = request("127.0.0.1", "203.0.113.9");

		assertThat(this.resolver.clientIp(request)).contains("203.0.113.9");
	}

	@Test
	void clientIp_untrustedPeerForwarding_isThePeersAddress() {
		MockHttpServletRequest request = request("198.51.100.7", "203.0.113.9");

		assertThat(this.resolver.clientIp(request)).contains("198.51.100.7");
	}

	@Test
	void clientIp_forwardedValueThatIsNoAddress_isThePeersAddress() {
		MockHttpServletRequest request = request("127.0.0.1", "not-an-address");

		assertThat(this.resolver.clientIp(request)).contains("127.0.0.1");
	}

	@Test
	void resolveArgument_hostedWithoutSignIn_isUnauthorizedWithBearer() {
		PrincipalResolver hosted = new PrincipalResolver(TestProperties.with("auth-mode=oauth"),
				new TrustedProxies(List.of(), Clock.systemUTC()));

		assertThatExceptionOfType(ApiException.class).isThrownBy(
				() -> hosted.resolveArgument(PARAMETER, null, new ServletWebRequest(request("127.0.0.1", "")), null))
			.satisfies((ex) -> {
				assertThat(ex.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
				assertThat(ex.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
			});
	}

	@Test
	void resolveArgument_withoutSignIn_isTheLocalUser() {
		Principal principal = this.resolver.resolveArgument(PARAMETER, null,
				new ServletWebRequest(request("198.51.100.7", "")), null);

		assertThat(principal).isEqualTo(new Principal("local", PrincipalKind.LOCAL, "198.51.100.7"));
	}

	private static MockHttpServletRequest request(String peer, String forwarded) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(peer);
		request.addHeader(PrincipalResolver.CLIENT_IP_HEADER, forwarded);
		return request;
	}

}
