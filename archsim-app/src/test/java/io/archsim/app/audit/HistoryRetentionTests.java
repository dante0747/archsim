package io.archsim.app.audit;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import io.archsim.app.config.TestProperties;
import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The history keeps each change, with who made it and from which address, for
 * {@code ARCHSIM_HISTORY_DAYS} (a year), then deletes it. Each test has a database of its
 * own, whose events are recorded in the order of their times, as the API records them.
 */
class HistoryRetentionTests {

	private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

	private final JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(TestDatabases.migratedJdbcUrl()));

	private final AuditRepository repository = new AuditRepository(this.jdbc);

	@Test
	void prune_eventOlderThanAYear_deletesIt() {
		event("old", NOW.minus(Duration.ofDays(366)));

		new HistoryRetention(this.repository, TestProperties.with()).prune(NOW);

		assertThat(events()).isEmpty();
	}

	@Test
	void prune_eventWithinAYear_keepsIt() {
		event("recent", NOW.minus(Duration.ofDays(364)));

		new HistoryRetention(this.repository, TestProperties.with()).prune(NOW);

		assertThat(events()).containsExactly("recent");
	}

	@Test
	void prune_historyDaysSet_keepsEventsThatLong() {
		event("old", NOW.minus(Duration.ofDays(8)));
		event("recent", NOW.minus(Duration.ofDays(6)));

		new HistoryRetention(this.repository, TestProperties.with("history-days=7")).prune(NOW);

		assertThat(events()).containsExactly("recent");
	}

	@Test
	void prune_moreOldEventsThanABatch_deletesThemAll() {
		this.jdbc.sql("""
				INSERT INTO audit_events (workspace_id, actor_kind, action, target_kind, target_id, created_at)
				SELECT 'default', 'user', 'run.create', 'run', 'old',
				       CAST(:start AS timestamptz) + make_interval(mins => n)
				FROM generate_series(1, 2500) AS n""")
			.param("start", Timestamp.from(NOW.minus(Duration.ofDays(400))))
			.update();
		event("recent", NOW.minus(Duration.ofDays(1)));

		int pruned = new HistoryRetention(this.repository, TestProperties.with()).prune(NOW);

		assertThat(pruned).isEqualTo(2500);
		assertThat(events()).containsExactly("recent");
	}

	/**
	 * Records a change to a project, as the API did at a time.
	 * @param projectId the project
	 * @param at when
	 */
	private void event(String projectId, Instant at) {
		this.jdbc.sql("""
				INSERT INTO audit_events (workspace_id, project_id, actor_kind, action, target_kind, target_id, ip,
				                          created_at)
				VALUES ('default', :project, 'user', 'project.update', 'project', :project, '203.0.113.7', :at)""")
			.param("project", projectId)
			.param("at", Timestamp.from(at))
			.update();
	}

	/**
	 * What the history holds.
	 * @return the target of each event, oldest first
	 */
	private List<String> events() {
		return this.jdbc.sql("SELECT target_id FROM audit_events ORDER BY id")
			.query((rows, number) -> rows.getString("target_id"))
			.list();
	}

}
