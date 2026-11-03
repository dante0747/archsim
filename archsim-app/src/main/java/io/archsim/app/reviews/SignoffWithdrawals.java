package io.archsim.app.reviews;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.web.ApiException;

/**
 * Sign-offs withdrawn: only by their reviewer, and not with a read-only API token.
 */
@Service
class SignoffWithdrawals {

	private final SignoffRepository signoffs;

	private final Access access;

	private final SignoffRecords records;

	SignoffWithdrawals(SignoffRepository signoffs, Access access, SignoffRecords records) {
		this.signoffs = signoffs;
		this.access = access;
		this.records = records;
	}

	/**
	 * Withdraws the asker's own sign-off.
	 * @param principal who is asking
	 * @param signoffId the sign-off
	 * @throws ApiException {@code 403} for a read-only token or someone else's sign-off,
	 * {@code 404} for a sign-off the asker may not see
	 */
	@Transactional
	void withdraw(Principal principal, String signoffId) {
		principal.requireWritable();
		String what = "Sign-off '" + signoffId + "'";
		StoredSignoff signoff = this.signoffs.find(signoffId).orElseThrow(() -> Access.notFound(what));
		this.access.require(principal, signoff.workspaceId(), WorkspaceRole.VIEWER, what);
		if (!principal.userId().equals(signoff.reviewerId())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "Only the reviewer can withdraw a sign-off.", List.of());
		}
		this.records.withdraw(signoff, principal);
	}

}
