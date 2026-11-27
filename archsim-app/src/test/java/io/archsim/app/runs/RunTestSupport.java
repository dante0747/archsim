package io.archsim.app.runs;

import java.util.List;
import java.util.UUID;
import java.util.function.LongFunction;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.database.TestDatabases;
import io.archsim.engine.run.QuickCheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

/**
 * What the runs' tests share: the whole application on one real PostgreSQL, as the local
 * user without sign-in, with the run preparation stood in for (a run is prepared as the
 * test says). Nothing simulates the runs: they stay queued unless a test says otherwise.
 * Each test uses projects of its own, so the tests share the context and the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class RunTestSupport {

	static final JsonMapper JSON = JsonMapper.builder().build();

	/** The design every stand-in preparation stores. */
	static final ObjectNode PREPARED = (ObjectNode) JSON.readTree("""
			{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "Prepared"},
			 "objectives": {"latencyP95Ms": 300.0}}""");

	private static final String DATABASE = TestDatabases.freshJdbcUrl();

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private RunPreparation preparation;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("archsim.database-url", () -> DATABASE);
	}

	MockMvcTester mvc() {
		return this.mvc;
	}

	JdbcClient jdbc() {
		return this.jdbc;
	}

	/**
	 * Connections of their own to the test database, for a transaction of their own, as
	 * another request at the same time has.
	 * @return the source of the connections
	 */
	static DataSource anotherDataSource() {
		return new DriverManagerDataSource(DATABASE);
	}

	RunPreparation preparation() {
		return this.preparation;
	}

	/**
	 * Makes the engine prepare every run as the design itself, with a seed: the given
	 * one, else the design's.
	 * @param seed the design's seed
	 * @param scenarioName the scenario's name, if a scenario is run
	 * @param quickCheck whether a quick check can answer it
	 */
	void prepares(long seed, @Nullable String scenarioName, boolean quickCheck) {
		prepares(seed, (chosen) -> prepared(chosen, scenarioName, quickCheck));
	}

	/**
	 * Makes the engine prepare each run as a function of its seed says, the given one
	 * else the design's.
	 * @param seed the design's seed
	 * @param preparation what the engine answers for a seed
	 */
	void prepares(long seed, LongFunction<PlannedRun> preparation) {
		willAnswer((invocation) -> {
			PrepareRequest request = invocation.getArgument(0);
			PlannedRun prepared = preparation.apply((request.seed() != null) ? request.seed() : seed);
			return new PlannedRun(request.scenarioId(), prepared.scenarioName(), prepared.seed(), prepared.document(),
					prepared.quickCheck());
		}).given(this.preparation).prepare(any());
	}

	/**
	 * A run as the engine prepares it.
	 * @param seed its seed
	 * @param scenarioName its scenario's name, if any
	 * @param quickCheck whether a quick check can answer it
	 * @return the prepared run
	 */
	static PlannedRun prepared(long seed, @Nullable String scenarioName, boolean quickCheck) {
		return new PlannedRun(null, scenarioName, seed, PREPARED, new QuickCheck(quickCheck,
				quickCheck ? "simulates 30 s" : "the traffic is not constant", 60.0, null, null));
	}

	/**
	 * A project in a workspace of its own the local user has a role in.
	 * @param kind the workspace's kind
	 * @param role the local user's role
	 * @return the project's id
	 */
	String project(String kind, String role) {
		String workspace = id();
		this.jdbc.sql("INSERT INTO workspaces (id, name, kind, created_at) VALUES (:id, 'Lab', :kind, now())")
			.param("id", workspace)
			.param("kind", kind)
			.update();
		this.jdbc
			.sql("INSERT INTO memberships (workspace_id, user_id, role, created_at) "
					+ "VALUES (:id, 'local', :role, now())")
			.param("id", workspace)
			.param("role", role)
			.update();
		String project = id();
		this.jdbc.sql("""
				INSERT INTO projects (id, workspace_id, created_by, name, document, schema_version, revision,
				                      created_at, updated_at)
				VALUES (:id, :workspace, 'local', 'Lab', CAST(:document AS jsonb), '1.7', 1, now(), now())""")
			.param("id", project)
			.param("workspace", workspace)
			.param("document", "{\"metadata\": {\"name\": \"Lab\"}, \"architecture\": {\"nodes\": []}}")
			.update();
		return project;
	}

	MvcTestResult start(String projectId, String body) {
		return post("/api/v1/projects/" + projectId + "/runs", body);
	}

	/**
	 * Starts runs as a request from an address does.
	 * @param address the address the request comes from
	 * @param projectId the project
	 * @param body the request's body
	 * @return the answer
	 */
	MvcTestResult startFrom(String address, String projectId, String body) {
		return this.mvc.post()
			.uri("/api/v1/projects/" + projectId + "/runs")
			.remoteAddress(address)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
	}

	MvcTestResult post(String uri, String body) {
		return this.mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	JsonNode started(MvcTestResult result) {
		assertThat(result).hasStatus(202);
		return json(result);
	}

	/**
	 * Marks a run as a worker leaves it.
	 * @param runId the run
	 * @param status {@code running}, {@code succeeded}, {@code failed} or
	 * {@code cancelled}
	 */
	void markStatus(String runId, String status) {
		this.jdbc.sql("UPDATE simulation_runs SET status = :status WHERE id = :id")
			.param("status", status)
			.param("id", runId)
			.update();
	}

	/**
	 * Finishes a run as a worker does, with a report and its compact summary.
	 * @param runId the run
	 * @param p95 the summary's p95 latency
	 */
	void finish(String runId, double p95) {
		finish(runId, p95, "{\"summary\": {\"latencyMs\": {\"p95\": " + p95 + "}}, \"nodes\": []}");
	}

	/**
	 * Finishes a run as a worker does, with a report of its own.
	 * @param runId the run
	 * @param p95 the compact summary's p95 latency
	 * @param report the report
	 */
	void finish(String runId, double p95, String report) {
		this.jdbc.sql("""
				UPDATE simulation_runs SET status = 'succeeded', progress = 1,
				       summary = CAST(:summary AS jsonb), report = CAST(:report AS jsonb)
				WHERE id = :id""")
			.param("summary", "{\"p95\": " + p95 + "}")
			.param("report", report)
			.param("id", runId)
			.update();
	}

	List<String> actions(String projectId) {
		return this.jdbc.sql("SELECT action FROM audit_events WHERE project_id = :id ORDER BY id")
			.param("id", projectId)
			.query((rows, number) -> rows.getString("action"))
			.list();
	}

	/**
	 * How many runs a project has.
	 * @param projectId the project
	 * @return its runs, whatever their status
	 */
	int runCount(String projectId) {
		return this.jdbc.sql("SELECT count(*) FROM simulation_runs WHERE project_id = :id")
			.param("id", projectId)
			.query(Integer.class)
			.single();
	}

	/**
	 * Gives a project's workspace its own limit of runs per hour.
	 * @param projectId the project
	 * @param runs the runs it may start per hour
	 */
	void runsPerHour(String projectId, int runs) {
		this.jdbc
			.sql("UPDATE workspaces SET max_runs_per_hour = :runs "
					+ "WHERE id = (SELECT workspace_id FROM projects WHERE id = :id)")
			.param("runs", runs)
			.param("id", projectId)
			.update();
	}

	/**
	 * Returns how many runs the local user has been counted for, in every window.
	 * @return the count
	 */
	int personsRunsCounted() {
		return counted("user:local:runs");
	}

	/**
	 * Returns how many hits a rate limit's key has been counted for, in every window.
	 * @param key the key, such as {@code user:local:runs}
	 * @return the count
	 */
	int counted(String key) {
		return this.jdbc.sql("SELECT coalesce(sum(count), 0) FROM rate_limits WHERE key = :key")
			.param("key", key)
			.query(Integer.class)
			.single();
	}

	static JsonNode json(MvcTestResult result) {
		return JSON.readTree(result.getResponse().getContentAsByteArray());
	}

	static String id() {
		return UUID.randomUUID().toString().replace("-", "");
	}

}
