package io.archsim.app.signin;

import java.time.Instant;

import org.springframework.stereotype.Component;

import io.archsim.app.retention.Retention;

/**
 * Browser sessions that expired: one signs no one in any more, and answers as a session
 * that never was, so its row, which holds the address and browser it started from, goes
 * at the next prune.
 */
@Component
class SessionRetention implements Retention {

	/** The most sessions one statement deletes. */
	private static final int BATCH = 1000;

	private final SessionRepository sessions;

	SessionRetention(SessionRepository sessions) {
		this.sessions = sessions;
	}

	@Override
	public String description() {
		return "expired sessions";
	}

	@Override
	public int prune(Instant now) {
		return Retention.inBatches(() -> this.sessions.deleteExpired(now, BATCH));
	}

}
