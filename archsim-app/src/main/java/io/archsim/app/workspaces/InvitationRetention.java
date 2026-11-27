package io.archsim.app.workspaces;

import java.time.Instant;

import org.springframework.stereotype.Component;

import io.archsim.app.retention.Retention;

/**
 * Invitations that expired, whether or not someone accepted them: a link used, expired or
 * never made answers alike, so an expired invitation's row, which holds the email it was
 * sent to, goes at the next prune.
 */
@Component
class InvitationRetention implements Retention {

	/** The most invitations one statement deletes. */
	private static final int BATCH = 1000;

	private final InvitationRepository invitations;

	InvitationRetention(InvitationRepository invitations) {
		this.invitations = invitations;
	}

	@Override
	public String description() {
		return "expired invitations";
	}

	@Override
	public int prune(Instant now) {
		return Retention.inBatches(() -> this.invitations.deleteExpired(now, BATCH));
	}

}
