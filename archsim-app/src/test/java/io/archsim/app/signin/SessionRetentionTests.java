package io.archsim.app.signin;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A browser session's row, with the address and browser it started from, goes once the
 * session expires: it signs no one in any more.
 */
class SessionRetentionTests extends HostedTestSupport {

	@Autowired
	private SessionRetention retention;

	@Test
	void prune_expiredSession_deletesIt() {
		SignedIn ada = signedIn("Ada");
		expire(ada);

		this.retention.prune(Instant.now());

		assertThat(sessionsOf(ada)).isZero();
	}

	@Test
	void prune_sessionBeforeItExpires_keepsItSignedIn() {
		SignedIn ada = signedIn("Ada");

		this.retention.prune(Instant.now());

		assertThat(send(ada, HttpMethod.GET, "/api/v1/auth/me", null)).hasStatusOk();
	}

	@Test
	void prune_moreExpiredSessionsThanABatch_deletesThemAll() {
		SignedIn ada = signedIn("Ada");
		jdbc().sql("""
				INSERT INTO sessions (id, user_id, created_at, last_seen_at, expires_at)
				SELECT md5(:user || n), :user, now(), now(), now() + interval '30 days'
				FROM generate_series(1, 2500) AS n""").param("user", ada.userId()).update();
		expire(ada);

		this.retention.prune(Instant.now());

		assertThat(sessionsOf(ada)).isZero();
	}

	private void expire(SignedIn person) {
		jdbc().sql("UPDATE sessions SET expires_at = now() - interval '1 second' WHERE user_id = :user")
			.param("user", person.userId())
			.update();
	}

	private int sessionsOf(SignedIn person) {
		return jdbc().sql("SELECT count(*) FROM sessions WHERE user_id = :user")
			.param("user", person.userId())
			.query(Integer.class)
			.single();
	}

}
