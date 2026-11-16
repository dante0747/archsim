package io.archsim.app.access;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.function.SingletonSupplier;

import io.archsim.app.store.Times;

/**
 * Browser sessions as Spring Security's context holds them: the {@code sessions} table,
 * read from the session cookie when the context is first asked for, and once. A request
 * that carries an {@code Authorization} header is signed in by that header or not at all,
 * so its session, if any, is never read; nor is a public endpoint's, as only an endpoint
 * that needs someone, or a change that needs a CSRF token, asks. Only the secret's
 * SHA-256 is stored, so a session ends at once on every instance when its row goes. How
 * recently a session was used is kept, but written at most every five minutes. A cookie
 * that signs no one in (no such session, an expired one, one of a disabled account)
 * leaves the context empty, and the request says why ({@link #refusal}). Starting a
 * session is signing in's, which sets the cookie itself.
 */
@Component
public final class BrowserSessions implements SecurityContextRepository {

	/** How stale a session's last-seen time may get before a request updates it. */
	private static final Duration TOUCH = Duration.ofMinutes(5);

	/** Where a request keeps why its session cookie signed no one in. */
	private static final String REFUSAL = BrowserSessions.class.getName() + ".refusal";

	private final CredentialRepository repository;

	private final Clock clock;

	private final SecurityContextHolderStrategy contexts;

	BrowserSessions(CredentialRepository repository, Clock clock,
			ObjectProvider<SecurityContextHolderStrategy> contexts) {
		this.repository = repository;
		this.clock = clock;
		this.contexts = contexts.getIfAvailable(SecurityContextHolder::getContextHolderStrategy);
	}

	@Override
	public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
		return new LoadedOnce(() -> read(request));
	}

	/**
	 * The request's security context, read at once.
	 * @deprecated Spring Security reads the context when it is first needed, through
	 * {@link #loadDeferredContext}; this is here because the interface still declares it
	 */
	@Override
	@Deprecated
	public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
		return read(requestResponseHolder.getRequest());
	}

	@Override
	public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
		// a session is started by signing in, which stores it and sets its cookie
	}

	@Override
	public boolean containsContext(HttpServletRequest request) {
		return sessionCookie(request).isPresent();
	}

	/**
	 * Why a request's session cookie signed no one in, once its context was read.
	 * @param request the request
	 * @return what to tell the browser, or empty when the cookie signed someone in, or
	 * there was none
	 */
	static Optional<String> refusal(HttpServletRequest request) {
		return Optional.ofNullable((String) request.getAttribute(REFUSAL));
	}

	/**
	 * Whether a request says who it is in its {@code Authorization} header, which signs
	 * it in, or not at all, whatever cookie it carries.
	 * @param request the request
	 * @return {@code true} when the header is there and not empty
	 */
	static boolean carriesAuthorization(HttpServletRequest request) {
		return StringUtils.hasLength(request.getHeader(HttpHeaders.AUTHORIZATION));
	}

	/**
	 * The session cookie a request carries.
	 * @param request the request
	 * @return the cookie's value, or empty when there is none, or it is empty
	 */
	static Optional<String> sessionCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return Optional.empty();
		}
		for (Cookie cookie : cookies) {
			if (Principal.SESSION_COOKIE.equals(cookie.getName()) && !cookie.getValue().isEmpty()) {
				return Optional.of(cookie.getValue());
			}
		}
		return Optional.empty();
	}

	private SecurityContext read(HttpServletRequest request) {
		if (carriesAuthorization(request)) {
			return this.contexts.createEmptyContext();
		}
		return sessionCookie(request).map((cookie) -> signIn(cookie, request))
			.orElseGet(this.contexts::createEmptyContext);
	}

	private SecurityContext signIn(String cookie, HttpServletRequest request) {
		Instant now = Times.now(this.clock);
		Optional<StoredSession> found = this.repository.session(Secrets.sha256Hex(cookie))
			.filter((stored) -> stored.active(now));
		if (found.isEmpty()) {
			return refuse(request, SignInRefusals.SESSION_EXPIRED);
		}
		StoredSession session = found.get();
		if (!this.repository.active(session.userId())) {
			return refuse(request, SignInRefusals.ACCOUNT_DISABLED);
		}
		if (Duration.between(session.lastSeenAt(), now).compareTo(TOUCH) > 0) {
			this.repository.touch(session, now);
		}
		SecurityContext context = this.contexts.createEmptyContext();
		context.setAuthentication(new PreAuthenticatedAuthenticationToken(
				new BrowserSession(session.userId(), session.id(), session.guest()), "", List.of()));
		return context;
	}

	private SecurityContext refuse(HttpServletRequest request, String why) {
		request.setAttribute(REFUSAL, why);
		return this.contexts.createEmptyContext();
	}

	/**
	 * A security context read when it is first asked for, and once: Spring Security asks
	 * the deferred context again at each look.
	 */
	private static final class LoadedOnce implements DeferredSecurityContext {

		private final SingletonSupplier<SecurityContext> context;

		LoadedOnce(Supplier<SecurityContext> load) {
			this.context = SingletonSupplier.of(load);
		}

		@Override
		public SecurityContext get() {
			return this.context.obtain();
		}

		@Override
		public boolean isGenerated() {
			return get().getAuthentication() == null;
		}

	}

}
