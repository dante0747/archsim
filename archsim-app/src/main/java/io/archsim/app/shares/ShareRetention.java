package io.archsim.app.shares;

import java.time.Instant;

import org.springframework.stereotype.Component;

import io.archsim.app.retention.Retention;

/**
 * The copies of share links that expired: an expired link answers {@code 404}, and its
 * expiry cannot be moved, so its frozen copy of the run's results goes at the next prune,
 * as revoking deletes it at once. The link stays in its workspace's lists, as expired.
 */
@Component
class ShareRetention implements Retention {

	/** The most copies one statement deletes: each holds a run's report. */
	private static final int BATCH = 100;

	private final ShareRepository shares;

	ShareRetention(ShareRepository shares) {
		this.shares = shares;
	}

	@Override
	public String description() {
		return "copies of expired share links";
	}

	@Override
	public int prune(Instant now) {
		return Retention.inBatches(() -> this.shares.clearExpiredPayloads(now, BATCH));
	}

}
