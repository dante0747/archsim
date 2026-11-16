package io.archsim.app.signin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.limits.WorkspaceLimits;
import io.archsim.app.store.Ids;
import io.archsim.app.store.Times;
import io.archsim.app.web.ApiException;
import io.archsim.app.workspaces.Workspaces;

/**
 * Sandboxes' guests (ADR-0079): visitors admitted without signing in, each owning a
 * sandbox (with the limits {@link WorkspaceLimits} gives the kind) for {@link #LIFETIME},
 * and deleted with it afterwards. The server keeps at most {@link #MOST} guests at once,
 * so that visitors cannot fill its database.
 */
@Service
class Guests {

	/** How long a guest and their sandbox last. */
	static final Duration LIFETIME = Duration.ofHours(24);

	/**
	 * The most guests the server keeps at once, expired ones not yet deleted included.
	 */
	static final int MOST = 500;

	/** The name of a guest's sandbox. */
	private static final String SANDBOX_NAME = "Sandbox";

	private final GuestRepository repository;

	private final Workspaces workspaces;

	private final Clock clock;

	Guests(GuestRepository repository, Workspaces workspaces, Clock clock) {
		this.repository = repository;
		this.workspaces = workspaces;
		this.clock = clock;
	}

	/**
	 * Adds a guest, in the caller's transaction: an account that owns a new sandbox, both
	 * deleted after {@link #LIFETIME}. Admissions take turns, so two cannot both take the
	 * last place.
	 * @return the guest
	 * @throws ApiException {@code 503} while the server keeps as many guests as it may
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Guest add() {
		// refused at once while full, without waiting for the turn; counted again in turn
		refuseWhenFull();
		this.repository.takeTurn();
		refuseWhenFull();
		Instant now = Times.now(this.clock);
		Guest guest = new Guest(Ids.newId(), now.plus(LIFETIME));
		this.repository.add(guest, now);
		this.workspaces.create(SANDBOX_NAME, WorkspaceLimits.SANDBOX, guest.id());
		return guest;
	}

	/**
	 * The guests whose sandboxes have expired.
	 * @return their user ids, the first to expire first
	 */
	List<String> expired() {
		return this.repository.expired(Times.now(this.clock));
	}

	/**
	 * Deletes a guest whose sandbox has expired with everything they had, in a
	 * transaction of its own: their sandbox, and all in it, and their sessions. Anyone
	 * else, a guest whose sandbox has not expired, and a guest another instance is
	 * deleting now, are left as they are.
	 * @param guestId the guest
	 * @return {@code true} when it deleted the guest
	 */
	@Transactional
	boolean delete(String guestId) {
		if (!this.repository.tryLockExpired(guestId, Times.now(this.clock))) {
			return false;
		}
		this.workspaces.deleteSandboxesOf(guestId);
		this.repository.delete(guestId);
		return true;
	}

	/**
	 * Refuses a new guest while every place is taken.
	 * @throws ApiException {@code 503} with {@code Retry-After}
	 */
	private void refuseWhenFull() {
		if (this.repository.count() >= MOST) {
			throw full(Times.now(this.clock));
		}
	}

	/**
	 * The refusal while every place is taken: until the first guest expires, in whole
	 * seconds rounded up, and never less than the time between prunes, as a guest that
	 * has expired already goes only at the next one.
	 * @param now the time
	 * @return {@code 503} with {@code Retry-After}
	 */
	private ApiException full(Instant now) {
		Duration untilFirst = this.repository.firstExpiry()
			.map((first) -> Duration.between(now, first))
			.orElse(LIFETIME);
		long seconds = Math.max(SandboxPruning.PERIOD.toSeconds(),
				Math.ceilDiv(untilFirst.toMillis(), Duration.ofSeconds(1).toMillis()));
		return ApiException.unavailable("Every sandbox is taken right now: sign in, or try again later.", seconds);
	}

}
