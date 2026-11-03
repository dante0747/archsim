package io.archsim.app.projects;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the projects' end-to-end tests share: the whole application on one real
 * PostgreSQL, as the local user without sign-in, and ways to set up the rows a test
 * needs. Each test uses workspaces of its own, so the tests share the context and the
 * database.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class ProjectTestSupport {

	static final JsonMapper JSON = JsonMapper.builder().build();

	/** The database every project test runs on, made once. */
	static final String DATABASE = TestDatabases.freshJdbcUrl();

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private JdbcClient jdbc;

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

	MvcTestResult post(String uri, String body) {
		return this.mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	MvcTestResult put(String uri, String body) {
		return this.mvc.put().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	static JsonNode created(MvcTestResult result) {
		assertThat(result).hasStatus(201);
		return json(result);
	}

	static JsonNode json(MvcTestResult result) {
		return JSON.readTree(result.getResponse().getContentAsByteArray());
	}

	/**
	 * Makes a workspace the local user has a role in.
	 * @param id its id, which no other test uses
	 * @param kind {@code personal}, {@code team} or {@code sandbox}
	 * @param role the local user's role there
	 */
	void workspace(String id, String kind, String role) {
		workspaceWithoutRole(id, kind);
		this.jdbc
			.sql("INSERT INTO memberships (workspace_id, user_id, role, created_at) "
					+ "VALUES (:id, 'local', :role, now())")
			.param("id", id)
			.param("role", role)
			.update();
	}

	/**
	 * Makes a workspace the local user has no role in.
	 * @param id its id, which no other test uses
	 * @param kind {@code personal}, {@code team} or {@code sandbox}
	 */
	void workspaceWithoutRole(String id, String kind) {
		this.jdbc.sql("INSERT INTO workspaces (id, name, kind, created_at) VALUES (:id, :id, :kind, now())")
			.param("id", id)
			.param("kind", kind)
			.update();
	}

	void user(String id) {
		this.jdbc.sql("INSERT INTO users (id, name, created_at) VALUES (:id, :id, now())").param("id", id).update();
	}

	/**
	 * Stores a project as another API would have, bypassing this one.
	 * @param id its id
	 * @param workspaceId its workspace
	 * @param createdBy who made it
	 * @param sourceExample the example it copies, if any
	 */
	void storedProject(String id, String workspaceId, String createdBy, @Nullable String sourceExample) {
		this.jdbc.sql("""
				INSERT INTO projects (id, workspace_id, created_by, name, document, schema_version, revision,
				                      source_example, created_at, updated_at)
				VALUES (:id, :workspace, :createdBy, 'Stored', CAST(:document AS jsonb), '1.7', 1, :example, now(),
				        now())""")
			.param("id", id)
			.param("workspace", workspaceId)
			.param("createdBy", createdBy)
			.param("example", sourceExample)
			.param("document", "{\"metadata\": {\"name\": \"Stored\"}, \"architecture\": {\"nodes\": []}}")
			.update();
	}

	/**
	 * The history of a project.
	 * @param projectId the project
	 * @return its events' actions, oldest first
	 */
	List<String> actions(String projectId) {
		return this.jdbc.sql("SELECT action FROM audit_events WHERE project_id = :id ORDER BY id")
			.param("id", projectId)
			.query((rows, number) -> rows.getString("action"))
			.list();
	}

}
