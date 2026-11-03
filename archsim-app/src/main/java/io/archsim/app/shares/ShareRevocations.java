package io.archsim.app.shares;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.web.ApiException;

/**
 * Share links revoked by the editors of their workspace. Revoking a revoked link changes
 * nothing more.
 */
@Service
class ShareRevocations {

	private final ShareRepository shares;

	private final Access access;

	private final ShareRecords records;

	ShareRevocations(ShareRepository shares, Access access, ShareRecords records) {
		this.shares = shares;
		this.access = access;
		this.records = records;
	}

	/**
	 * Stops a link working, at once: the page answers {@code 404} and the stored copy is
	 * deleted.
	 * @param principal who is asking
	 * @param shareId the share
	 * @throws ApiException {@code 404} for a share the asker may not see, {@code 403} for
	 * a viewer
	 */
	@Transactional
	void revoke(Principal principal, String shareId) {
		String what = "Share link";
		StoredShare share = this.shares.find(shareId).orElseThrow(() -> Access.notFound(what));
		this.access.require(principal, share.workspaceId(), WorkspaceRole.EDITOR, what);
		this.records.revoke(share, principal);
	}

}
