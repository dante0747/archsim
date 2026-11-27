package io.archsim.app.shares;

import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration (V3) that hides the telemetry addresses share links stored before they
 * hid them all: it rewrites each stored copy as {@link SharePayloads#hideAddresses}
 * writes one, whatever the strings hold, so the SQL and the Java cannot part.
 */
class StoredSharesMigrationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The last version before the migration. */
	private static final String BEFORE = "2";

	private static final String PROMETHEUS = "Prometheus https://scraper:hunter2@prom.internal.example:9090";

	/**
	 * Copies as links made before kept them: measured inputs, calibrations, and what
	 * stays.
	 */
	static List<JsonNode> storedCopies() {
		return List.of(copy(measured(PROMETHEUS)),
				copy(measured("From the load test.\nhttps://grafana.example.com/d/abc")),
				copy(measured("API\thttps://api.example.com and\r\nhttp://10.0.0.7:9090")),
				copy(measured("Prometheus https://user:pa\"ss@prom.internal:9090")),
				copy(NODES.objectNode().put("kind", "documented").put("source", "Go 1.8 https://go.dev/doc/go1.8")),
				calibrated(PROMETHEUS));
	}

	@ParameterizedTest
	@MethodSource("storedCopies")
	void migrate_aStoredCopy_isRewrittenAsTheApiNowWritesOne(JsonNode stored) {
		DataSource database = databaseBefore();
		store(database, "s1", stored.toString());

		migrate(database);

		assertThat(read(database, "s1")).isEqualTo(SharePayloads.hideAddresses(stored));
	}

	@Test
	void migrate_aCopyWithTelemetryAddresses_publishesNone() {
		DataSource database = databaseBefore();
		store(database, "s1", copy(measured(PROMETHEUS)).toString());

		migrate(database);

		assertThat(read(database, "s1").toString()).doesNotContain("hunter2").doesNotContain("prom.internal");
	}

	@Test
	void migrate_aRevokedLinksCopy_staysRevoked() {
		DataSource database = databaseBefore();
		store(database, "s1", "null");

		migrate(database);

		assertThat(read(database, "s1").isNull()).isTrue();
	}

	/** A stored copy whose design and report name an input's provenance. */
	private static ObjectNode copy(ObjectNode provenance) {
		ObjectNode copy = NODES.objectNode().put("projectName", "Shop");
		ObjectNode report = copy.putObject("report");
		report.putArray("parts")
			.addObject()
			.putArray("explain")
			.addObject()
			.put("name", "Little's law")
			.put("source", "https://en.wikipedia.org/wiki/Little%27s_law");
		report.putObject("assumptions")
			.putArray("userInputs")
			.addObject()
			.put("field", "serviceTimeMs")
			.set("provenance", provenance.deepCopy());
		ObjectNode document = copy.putObject("document");
		document.putObject("metadata").put("description", "Runbook: http://wiki.internal/shop");
		document.putObject("provenance").set("nodes/api/serviceTimeMs", provenance);
		return copy;
	}

	/** A stored copy whose design's calibration names its source. */
	private static ObjectNode calibrated(String source) {
		ObjectNode copy = NODES.objectNode().put("projectName", "Shop");
		copy.putObject("document").putObject("calibration").put("source", source).put("inputs", 2);
		copy.putObject("report").putObject("assumptions").putObject("calibration").put("source", source);
		return copy;
	}

	private static ObjectNode measured(String source) {
		return NODES.objectNode().put("kind", "measured").put("source", source).put("query", "rate(requests[5m])");
	}

	private static DataSource databaseBefore() {
		DataSource database = new DriverManagerDataSource(TestDatabases.freshJdbcUrl());
		Flyway.configure().dataSource(database).target(BEFORE).load().migrate();
		JdbcClient.create(database)
			.sql("INSERT INTO workspaces (id, name, kind, created_at) VALUES ('w1', 'Team', 'team', now())")
			.update();
		return database;
	}

	private static void migrate(DataSource database) {
		Flyway.configure().dataSource(database).load().migrate();
	}

	private static void store(DataSource database, String id, String payload) {
		JdbcClient.create(database)
			.sql("""
					INSERT INTO shared_reports (id, slug, workspace_id, title, payload, payload_version, report_version,
					                            created_at)
					VALUES (:id, :id, 'w1', 'Shop', CAST(:payload AS jsonb), 1, '1.21', now())""")
			.param("id", id)
			.param("payload", payload)
			.update();
	}

	private static JsonNode read(DataSource database, String id) {
		String payload = JdbcClient.create(database)
			.sql("SELECT payload::text FROM shared_reports WHERE id = :id")
			.param("id", id)
			.query(String.class)
			.single();
		return JSON.readTree(payload);
	}

}
