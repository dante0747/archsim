package io.archsim.app.tokens;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import io.archsim.app.access.Access;
import io.archsim.app.access.Person;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.access.Secrets;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.store.Ids;
import io.archsim.app.store.Times;
import io.archsim.app.web.ApiException;

/**
 * Personal API tokens. A token acts as the person who made it, with their role in each
 * workspace, lowered to viewer for a {@code read} token and limited to one workspace when
 * it names one. The secret is shown once; only its SHA-256 is stored, so a lost token is
 * revoked and replaced, not recovered.
 */
@Service
class Tokens {

	/** Active tokens one person keeps. */
	private static final int MAX_TOKENS = 50;

	/** The characters of a token shown to tell it apart: its prefix and four more. */
	private static final int SHOWN = Secrets.TOKEN_PREFIX.length() + 4;

	private final TokenRepository repository;

	private final Access access;

	private final RateLimits limits;

	private final Clock clock;

	Tokens(TokenRepository repository, Access access, RateLimits limits, Clock clock) {
		this.repository = repository;
		this.access = access;
		this.limits = limits;
		this.clock = clock;
	}

	/**
	 * A person's tokens that still work.
	 * @param person who is asking
	 * @return the tokens, newest first
	 */
	List<TokenView> list(Person person) {
		return this.repository.active(person.userId(), now());
	}

	/**
	 * Makes a token.
	 * @param person who is asking
	 * @param request what the token is for and may do
	 * @return the token with its secret
	 * @throws ApiException {@code 429} after many tokens in a day, {@code 404} for a
	 * workspace the person is not in, {@code 409} with 50 active tokens
	 */
	TokenCreated create(Person person, TokenCreate request) {
		this.limits.enforce("user:" + person.userId() + ":tokens", RateLimit.TOKENS_PER_USER,
				"You have made many tokens today.");
		if (request.workspaceId() != null) {
			this.access.workspace(person.principal(), new RequestedWorkspace(request.workspaceId()));
		}
		int active = list(person).size();
		if (active >= MAX_TOKENS) {
			throw ApiException.conflict("You have " + active + " tokens: revoke some first.");
		}
		String secret = Secrets.newSecret(Secrets.TOKEN_PREFIX);
		Instant now = now();
		String name = request.name().strip();
		Integer days = request.expiresInDays();
		TokenView token = new TokenView(Ids.newId(), name.isEmpty() ? "token" : name, secret.substring(0, SHOWN),
				request.scope().json(), request.workspaceId(), now, null,
				(days != null) ? now.plus(Duration.ofDays(days)) : null, null);
		this.repository.add(token, person.userId(), Secrets.sha256Hex(secret));
		return TokenCreated.of(token, secret);
	}

	/**
	 * Stops a token working, at once and everywhere.
	 * @param person who is asking
	 * @param tokenId the token
	 * @throws ApiException {@code 404} unless it is the person's
	 */
	void revoke(Person person, String tokenId) {
		if (this.repository.ownerOf(tokenId).filter(person.userId()::equals).isEmpty()) {
			throw Access.notFound("Token");
		}
		this.repository.revoke(tokenId, now());
	}

	private Instant now() {
		return Times.now(this.clock);
	}

}
