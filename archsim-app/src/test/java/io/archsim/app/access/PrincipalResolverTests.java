package io.archsim.app.access;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.context.request.ServletWebRequest;

import io.archsim.app.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

/**
 * Who is asking, as the resolver reads it from Spring Security's context: the local user,
 * and the refusal of a request that signs no one in. Sessions and tokens are
 * {@link HostedPrincipalTests}' and {@link TokenAuthenticationTests}'.
 */
class PrincipalResolverTests {

	private static final MethodParameter PARAMETER = mock();

	private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

	private final PrincipalResolver resolver = new PrincipalResolver(
			new ClientIps(new TrustedProxies(List.of(), Clock.systemUTC())), this.contexts);

	@AfterEach
	void clearContext() {
		this.contexts.clearContext();
	}

	@Test
	void resolveArgument_nobodyInTheContext_isUnauthorizedWithBearer() {
		ServletWebRequest request = new ServletWebRequest(ClientIpsTests.request("127.0.0.1", ""));

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> this.resolver.resolveArgument(PARAMETER, null, request, null))
			.satisfies((ex) -> {
				assertThat(ex.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
				assertThat(ex.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
				assertThat(ex.getMessage()).isEqualTo("Sign in to continue");
			});
	}

	@Test
	void resolveArgument_localUserInTheContext_isTheLocalUser() {
		SecurityContext context = this.contexts.createEmptyContext();
		context.setAuthentication(new PreAuthenticatedAuthenticationToken(new LocalUser(), "", List.of()));
		this.contexts.setContext(context);

		Object principal = this.resolver.resolveArgument(PARAMETER, null,
				new ServletWebRequest(ClientIpsTests.request("198.51.100.7", "")), null);

		assertThat(principal).isEqualTo(Principal.local("198.51.100.7"));
	}

}
