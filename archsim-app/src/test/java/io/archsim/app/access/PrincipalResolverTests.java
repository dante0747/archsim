package io.archsim.app.access;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.ServletWebRequest;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.config.TestProperties;
import io.archsim.app.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

/**
 * Who is asking without sign-in, and the refusal of a signed-out request with it. Signing
 * in with a session or a token is {@link HostedPrincipalTests}'.
 */
class PrincipalResolverTests {

	private static final MethodParameter PARAMETER = mock();

	private static final StaticListableBeanFactory NO_BEANS = new StaticListableBeanFactory();

	@Test
	void resolveArgument_hostedWithoutSignIn_isUnauthorizedWithBearer() {
		PrincipalResolver hosted = resolver(TestProperties.with("auth-mode=oauth"));

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> hosted.resolveArgument(PARAMETER, null,
					new ServletWebRequest(ClientIpsTests.request("127.0.0.1", "")), null))
			.satisfies((ex) -> {
				assertThat(ex.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
				assertThat(ex.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
			});
	}

	@Test
	void resolveArgument_withoutSignIn_isTheLocalUser() {
		Object principal = resolver(TestProperties.with()).resolveArgument(PARAMETER, null,
				new ServletWebRequest(ClientIpsTests.request("198.51.100.7", "")), null);

		assertThat(principal).isEqualTo(Principal.local("198.51.100.7"));
	}

	private static PrincipalResolver resolver(ArchsimProperties properties) {
		return new PrincipalResolver(properties, new ClientIps(new TrustedProxies(List.of(), Clock.systemUTC())),
				NO_BEANS.getBeanProvider(Credentials.class), NO_BEANS.getBeanProvider(Csrf.class));
	}

}
