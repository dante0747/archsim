package io.archsim.app.shares;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Principal;
import io.archsim.app.audit.AuditLog;
import io.archsim.app.audit.Change;
import io.archsim.app.store.Ids;
import io.archsim.app.store.Times;

/**
 * Share links made and revoked, each with its line in the history. Revoking deletes the
 * frozen copy at once.
 */
@Component
class ShareRecords {

	private final ShareRepository shares;

	private final AuditLog audit;

	private final Clock clock;

	ShareRecords(ShareRepository shares, AuditLog audit, Clock clock) {
		this.shares = shares;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Stores a new share link, with a new slug, and its line in the history, in one
	 * transaction.
	 * @param share what it shares
	 * @param principal who makes it
	 * @return the share
	 */
	@Transactional
	StoredShare add(NewShare share, Principal principal) {
		Instant now = Times.now(this.clock);
		Integer days = share.expiresInDays();
		StoredShare made = new StoredShare(Ids.newId(), Slugs.next(), share.workspaceId(), share.run().projectId(),
				share.run().id(), principal.userId(), share.title(), share.payload(), SharePayloads.VERSION,
				share.reportVersion(), share.run().resultDigest(), now,
				(days != null) ? now.plus(Duration.ofDays(days)) : null, null, 0);
		this.shares.add(made);
		Map<String, @Nullable Object> meta = new LinkedHashMap<>();
		meta.put("title", made.title());
		meta.put("runId", share.run().id());
		meta.put("label", share.run().label());
		record("share.create", made, meta, principal);
		return made;
	}

	/**
	 * Stops a link working, at once: stamps it revoked and deletes its frozen copy. Only
	 * the call that stamps it records the change, so revoking twice at once is one line
	 * in the history.
	 * @param share the share
	 * @param principal who revokes it
	 */
	void revoke(StoredShare share, Principal principal) {
		if (this.shares.stampRevoked(share.id(), Times.now(this.clock))) {
			record("share.revoke", share, Map.of("title", share.title()), principal);
		}
		this.shares.clearPayload(share.id());
	}

	private void record(String action, StoredShare share, Map<String, @Nullable Object> meta, Principal principal) {
		this.audit.record(new Change(share.workspaceId(), share.projectId(), action, "share", share.id(), meta),
				principal);
	}

}
