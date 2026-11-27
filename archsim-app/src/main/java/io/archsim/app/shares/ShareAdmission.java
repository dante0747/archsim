package io.archsim.app.shares;

import org.springframework.stereotype.Component;

import io.archsim.app.access.Principal;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.limits.WorkspaceStorage;
import io.archsim.app.web.ApiException;

/**
 * What a new share link must find before it is made: room in its workspace's quota, as it
 * stores a copy of a run's results (ADR-0109), then a place in its maker's daily limit. A
 * link the quota refuses is never charged.
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
	 * Admits a new share link, or refuses it.
	 * @param principal who makes it
	 * @param workspaceId the workspace it is made in
	 * @throws ApiException {@code 409} when the workspace stores its quota or more,
	 * {@code 429} after many links in a day
	 */
	void admit(Principal principal, String workspaceId) {
		this.storage.requireRoom(workspaceId);
		this.limits.enforce("user:" + principal.userId() + ":shares", RateLimit.SHARES_PER_USER,
				"You have made many share links today.");
	}

}
