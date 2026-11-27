package io.archsim.app.retention;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import io.archsim.app.ArchsimApplication;
import io.archsim.app.Role;
import io.archsim.app.database.TestDatabases;
import io.archsim.app.support.StoredDesigns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What the API prunes when it starts, from a database that holds rows it no longer keeps:
 * expired sessions and invitations, the copies of expired share links, the history older
 * than it keeps, and the runs past their project's limit.
 */
class StartupPruningTests {

	private static final Duration A_WHILE = Duration.ofSeconds(30);

	private final String database = TestDatabases.migratedJdbcUrl();

	private final JdbcClient seed = JdbcClient.create(new DriverManagerDataSource(this.database));

	@Test
	void start_theApiRole_deletesExpiredSessions() {
		this.seed.sql("""
				INSERT INTO sessions (id, user_id, created_at, last_seen_at, expires_at)
				VALUES ('expired', 'local', now() - interval '31 days', now() - interval '31 days',
				        now() - interval '1 day'),
				       ('current', 'local', now(), now(), now() + interval '30 days')""").update();

		try (ConfigurableApplicationContext api = startApi()) {
			await().atMost(A_WHILE).untilAsserted(() -> assertThat(ids(api, "sessions")).containsExactly("current"));
		}
	}

	@Test
	void start_theApiRole_deletesExpiredInvitations() {
		this.seed.sql("""
				INSERT INTO invitations (id, workspace_id, token_hash, role, created_at, expires_at)
				VALUES ('expired', 'default', 'hash-expired', 'viewer', now() - interval '8 days',
				        now() - interval '1 day'),
				       ('current', 'default', 'hash-current', 'viewer', now(), now() + interval '7 days')""").update();

		try (ConfigurableApplicationContext api = startApi()) {
			await().atMost(A_WHILE).untilAsserted(() -> assertThat(ids(api, "invitations")).containsExactly("current"));
		}
	}

	@Test
	void start_theApiRole_deletesTheCopiesOfExpiredShareLinks() {
		this.seed.sql("""
				INSERT INTO shared_reports (id, slug, workspace_id, title, payload, payload_version, report_version,
				                            created_at, expires_at)
				VALUES ('expired', 'expired', 'default', 'Old', '{}', 1, '1.4', now() - interval '31 days',
				        now() - interval '1 day'),
				       ('current', 'current', 'default', 'New', '{}', 1, '1.4', now(), now() + interval '30 days')""")
			.update();

		try (ConfigurableApplicationContext api = startApi()) {
			await().atMost(A_WHILE)
				.untilAsserted(() -> assertThat(
						list(api, "SELECT id || ':' || (payload IS NOT NULL) FROM shared_reports ORDER BY id"))
					.containsExactly("current:true", "expired:false"));
		}
	}

	@Test
	void start_theApiRole_deletesTheHistoryOlderThanItKeeps() {
		this.seed.sql("""
				INSERT INTO audit_events (workspace_id, actor_kind, action, target_kind, target_id, created_at)
				VALUES ('default', 'user', 'project.create', 'project', 'old', now() - interval '400 days')""")
			.update();
		this.seed.sql("""
				INSERT INTO audit_events (workspace_id, actor_kind, action, target_kind, target_id, created_at)
				VALUES ('default', 'user', 'project.update', 'project', 'recent', now() - interval '1 day')""")
			.update();

		try (ConfigurableApplicationContext api = startApi()) {
			await().atMost(A_WHILE)
				.untilAsserted(() -> assertThat(list(api, "SELECT target_id FROM audit_events ORDER BY id"))
					.containsExactly("recent"));
		}
	}

	@Test
	void start_theApiRole_deletesTheRunsPastTheirProjectsLimit() {
		this.seed.sql("""
				INSERT INTO projects (id, workspace_id, name, document, schema_version, revision, created_at,
				                      updated_at)
				VALUES ('lab', 'default', 'Lab', '{}', '1.7', 1, now(), now())""").update();
		this.seed.sql("""
				INSERT INTO simulation_runs (id, project_id, workspace_id, status, progress, seed, engine_version,
				                             design_digest, created_at)
				VALUES ('oldest', 'lab', 'default', 'succeeded', 1, 1, 'test', :design, now() - interval '3 minutes'),
				       ('middle', 'lab', 'default', 'succeeded', 1, 1, 'test', :design, now() - interval '2 minutes'),
				       ('newest', 'lab', 'default', 'succeeded', 1, 1, 'test', :design, now() - interval '1 minute')""")
			.param("design", StoredDesigns.store(this.seed, "{}"))
			.update();

		try (ConfigurableApplicationContext api = startApi("--archsim.run-history-limit=2")) {
			await().atMost(A_WHILE)
				.untilAsserted(() -> assertThat(ids(api, "simulation_runs")).containsExactly("middle", "newest"));
		}
	}

	private ConfigurableApplicationContext startApi(String... settings) {
		String[] args = Stream
			.concat(Stream.of("--archsim.database-url=" + this.database, "--server.port=0"), Stream.of(settings))
			.toArray(String[]::new);
		return new SpringApplicationBuilder(ArchsimApplication.class).web(Role.API.webApplicationType())
			.profiles(Role.API.profile())
			.run(args);
	}

	private static List<String> ids(ConfigurableApplicationContext api, String table) {
		return list(api, "SELECT id FROM " + table + " ORDER BY id");
	}

	/**
	 * What a query of one text column answers, through the API's own connections.
	 * @param api the started API
	 * @param query the query
	 * @return its rows' values, in its order
	 */
	private static List<String> list(ConfigurableApplicationContext api, String query) {
		return api.getBean(JdbcClient.class).sql(query).query((rows, number) -> rows.getString(1)).list();
	}

}
