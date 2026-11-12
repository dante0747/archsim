package io.archsim.app.access;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.stereotype.Component;

import io.archsim.app.store.Times;

/**
 * Reads a personal API token ({@code asim_pat_...}) from the {@code api_tokens} table,
 * for Spring Security's bearer token filter. Only the token's SHA-256 is stored, so a
 * token is revoked at once on every instance by marking its row. How recently each was
 * used is kept, but written at most once a minute.
 */
@Component
final class ApiTokenIntrospector implements OpaqueTokenIntrospector {

	/** How stale a token's last-used time may get before a request updates it. */
	private static final Duration TOUCH = Duration.ofMinutes(1);

	private final CredentialRepository repository;

	private final Clock clock;

	ApiTokenIntrospector(CredentialRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Override
	public OAuth2AuthenticatedPrincipal introspect(String token) {
		if (!token.startsWith(Secrets.TOKEN_PREFIX)) {
			throw new BadOpaqueTokenException(SignInRefusals.SEND_A_TOKEN);
		}
		Instant now = Times.now(this.clock);
		StoredToken stored = this.repository.token(Secrets.sha256Hex(token))
			.filter((candidate) -> candidate.active(now))
			.orElseThrow(() -> new BadOpaqueTokenException(SignInRefusals.TOKEN_NOT_VALID));
		if (!this.repository.active(stored.userId())) {
			throw new BadOpaqueTokenException(SignInRefusals.ACCOUNT_DISABLED);
		}
		Instant used = stored.lastUsedAt();
		if (used == null || Duration.between(used, now).compareTo(TOUCH) > 0) {
			this.repository.touch(stored, now);
		}
		return new ApiTokenPrincipal(stored.userId(), stored.grant());
	}

}
