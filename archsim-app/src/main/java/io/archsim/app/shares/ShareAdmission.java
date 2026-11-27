package io.archsim.app.shares;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.archsim.app.access.Principal;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.limits.WorkspaceStorage;
import io.archsim.app.web.ApiException;

/**
 * Admits a new share link, which stores a copy of a run's results (ADR-0109): it needs
 * room in its workspace's quota, then a place in its maker's daily limit. The quota is
 * checked first, so a link it refuses is never charged; the limit is charged before the
 * link's transaction begins (ADR-0105); and the link is made in a transaction that checks
 * the quota again with the workspace locked ({@link WorkspaceStorage#writeInTurn}), so it
 * sees what writes made at once stored. A link that second check refuses has been
 * charged.
 */
@Component
class ShareAdmission {

	private final WorkspaceStorage storage;

	private final RateLimits limits;

	ShareAdmission(WorkspaceStorage storage, RateLimits limits) {
		this.storage = storage;
		this.limits = limits;
	}

	/**
	 * Admits a new share link and makes it, or refuses it.
	 * @param principal who makes it
	 * @param workspaceId the workspace it is made in
	 * @param making what stores it, in the transaction the admission begins
	 * @return the link
	 * @throws ApiException {@code 409} when the workspace stores its quota or more,
	 * {@code 429} after many links in a day
	 */
	StoredShare admit(Principal principal, String workspaceId, Supplier<StoredShare> making) {
		this.storage.requireRoom(workspaceId);
		this.limits.enforce("user:" + principal.userId() + ":shares", RateLimit.SHARES_PER_USER,
				"You have made many share links today.");
		return this.storage.writeInTurn(workspaceId, making);
	}

}
