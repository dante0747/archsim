package io.archsim.app.access;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolderStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

/**
 * Browser sessions in Spring Security's context: read from the session cookie only when a
 * request first asks who it is, so a public endpoint never reads the table.
 */
class BrowserSessionsTests {

	private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");

	private static final String SECRET = "the-session-secret";

	private final CredentialRepository repository = mock();

	private final BrowserSessions sessions = new BrowserSessions(this.repository, Clock.fixed(NOW, ZoneOffset.UTC),
			new StaticListableBeanFactory().getBeanProvider(SecurityContextHolderStrategy.class));

	@Test
	void loadDeferredContext_withoutCookie_isEmptyAndReadsNoSession() {
		DeferredSecurityContext context = this.sessions.loadDeferredContext(new MockHttpServletRequest());

		assertThat(context.get().getAuthentication()).isNull();
		then(this.repository).shouldHaveNoInteractions();
	}

	@Test
	void loadDeferredContext_withCookie_readsNoSessionUntilAsked() {
		this.sessions.loadDeferredContext(withCookie());

		then(this.repository).shouldHaveNoInteractions();
	}

	@Test
	void loadDeferredContext_askedTwice_readsTheSessionOnce() {
		String id = Secrets.sha256Hex(SECRET);
		given(this.repository.session(id))
			.willReturn(Optional.of(new StoredSession(id, "ada", NOW.plusSeconds(60), NOW, false)));
		given(this.repository.active("ada")).willReturn(true);
		DeferredSecurityContext context = this.sessions.loadDeferredContext(withCookie());

		SecurityContext first = context.get();
		SecurityContext second = context.get();

		assertThat(second).isSameAs(first);
		then(this.repository).should(times(1)).session(id);
	}

	@Test
	void loadDeferredContext_withAuthorizationHeader_readsNoSession() {
		MockHttpServletRequest request = withCookie();
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer asim_pat_token");

		DeferredSecurityContext context = this.sessions.loadDeferredContext(request);

		assertThat(context.get().getAuthentication()).isNull();
		assertThat(BrowserSessions.refusal(request)).isEmpty();
		then(this.repository).shouldHaveNoInteractions();
	}

	@Test
	void loadDeferredContext_activeSession_signsItsPersonIn() {
		String id = Secrets.sha256Hex(SECRET);
		given(this.repository.session(id))
			.willReturn(Optional.of(new StoredSession(id, "ada", NOW.plusSeconds(60), NOW, false)));
		given(this.repository.active("ada")).willReturn(true);

		DeferredSecurityContext context = this.sessions.loadDeferredContext(withCookie());

		assertThat(context.get().getAuthentication()).isNotNull()
			.satisfies((authentication) -> assertThat(authentication.getPrincipal())
				.isEqualTo(new BrowserSession("ada", id, false)));
	}

	@Test
	void loadDeferredContext_guestsSession_signsTheGuestIn() {
		String id = Secrets.sha256Hex(SECRET);
		given(this.repository.session(id))
			.willReturn(Optional.of(new StoredSession(id, "guest", NOW.plusSeconds(60), NOW, true)));
		given(this.repository.active("guest")).willReturn(true);

		DeferredSecurityContext context = this.sessions.loadDeferredContext(withCookie());

		assertThat(context.get().getAuthentication()).isNotNull()
			.satisfies((authentication) -> assertThat(authentication.getPrincipal())
				.isEqualTo(new BrowserSession("guest", id, true)));
	}

	@Test
	void loadDeferredContext_expiredSession_signsNoOneInAndSaysWhy() {
		String id = Secrets.sha256Hex(SECRET);
		given(this.repository.session(id)).willReturn(Optional.of(new StoredSession(id, "ada", NOW, NOW, false)));
		MockHttpServletRequest request = withCookie();

		DeferredSecurityContext context = this.sessions.loadDeferredContext(request);

		assertThat(context.get().getAuthentication()).isNull();
		assertThat(BrowserSessions.refusal(request)).hasValue("Your sign-in has expired: sign in again");
	}

	private static MockHttpServletRequest withCookie() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new Cookie(Principal.SESSION_COOKIE, SECRET));
		return request;
	}

}
