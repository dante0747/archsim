package io.archsim.app.support;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.apitests.FakeGitHub;
import io.archsim.app.access.Csrf;
import io.archsim.app.access.Principal;
import io.archsim.app.access.Secrets;
import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * What the tests of a server with sign-in share: the whole application on one real
 * PostgreSQL, GitHub sign-in against the black-box suite's stand-in, and ways to make the
 * people, sessions and tokens a test needs. Each test makes people of its own, so the
 * tests share the context and the database.
 */
@SpringBootTest(properties = { "archsim.auth-mode=oauth", "archsim.secret-key=" + HostedTestSupport.SECRET_KEY,
		"archsim.public-url=" + HostedTestSupport.PUBLIC_URL, "archsim.github-client-id=" + FakeGitHub.CLIENT_ID,
		"archsim.github-client-secret=" + FakeGitHub.CLIENT_SECRET,
		"archsim.admin-emails=" + HostedTestSupport.ADMIN_EMAIL })
@AutoConfigureMockMvc
public abstract class HostedTestSupport {

	/** Signs the state cookies and derives the CSRF tokens. */
	public static final String SECRET_KEY = "test-secret-key-0123456789-abcdefghijklmnop";

	/** Where sign-in callbacks go. */
	public static final String PUBLIC_URL = "https://archsim.test";

	/** Whose first sign-in owns the default workspace. */
	public static final String ADMIN_EMAIL = "admin@example.com";

	private static final String DATABASE = TestDatabases.freshJdbcUrl();

	private static final FakeGitHub GITHUB = FakeGitHub.start(0);

	private static final Duration SESSION_LENGTH = Duration.ofDays(30);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private Csrf csrf;

	@DynamicPropertySource
	static void settings(DynamicPropertyRegistry registry) {
		registry.add("archsim.database-url", () -> DATABASE);
		registry.add("archsim.github-url", () -> GITHUB.url().toString());
		registry.add("archsim.github-api-url", () -> GITHUB.url().toString());
	}

	protected MockMvcTester mvc() {
		return this.mvc;
	}

	protected JdbcClient jdbc() {
		return this.jdbc;
	}

	protected static FakeGitHub github() {
		return GITHUB;
	}

	/**
	 * A connection of its own to the test database, as another request at the same time
	 * has.
	 * @return the client
	 */
	protected static JdbcClient anotherConnection() {
		return JdbcClient.create(new DriverManagerDataSource(DATABASE));
	}

	/**
	 * A person with a personal workspace and a browser session, as signing in leaves
	 * them.
	 * @param name the person's name
	 * @return the person and their session
	 */
	protected SignedIn signedIn(String name) {
		String userId = newId();
		String workspaceId = newId();
		this.jdbc.sql("INSERT INTO users (id, name, created_at) VALUES (:id, :name, now())")
			.param("id", userId)
			.param("name", name)
			.update();
		this.jdbc
			.sql("INSERT INTO workspaces (id, name, kind, created_by, created_at) "
					+ "VALUES (:id, :name, 'personal', :user, now())")
			.param("id", workspaceId)
			.param("name", name + "'s workspace")
			.param("user", userId)
			.update();
		member(workspaceId, userId, "owner");
		return session(userId, workspaceId);
	}

	/**
	 * Another browser session of someone signed in.
	 * @param userId the person
	 * @param workspaceId their personal workspace
	 * @return the session
	 */
	protected SignedIn session(String userId, String workspaceId) {
		String secret = Secrets.newSecret("");
		String id = Secrets.sha256Hex(secret);
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		this.jdbc
			.sql("INSERT INTO sessions (id, user_id, created_at, last_seen_at, expires_at) "
					+ "VALUES (:id, :user, :now, :now, :expires)")
			.param("id", id)
			.param("user", userId)
			.param("now", Timestamp.from(now))
			.param("expires", Timestamp.from(now.plus(SESSION_LENGTH)))
			.update();
		return new SignedIn(userId, workspaceId, secret, this.csrf.token(id));
	}

	/**
	 * Gives someone a role in a workspace.
	 * @param workspaceId the workspace
	 * @param userId the person
	 * @param role {@code viewer}, {@code editor} or {@code owner}
	 */
	protected void member(String workspaceId, String userId, String role) {
		this.jdbc
			.sql("INSERT INTO memberships (workspace_id, user_id, role, created_at) "
					+ "VALUES (:workspace, :user, :role, now())")
			.param("workspace", workspaceId)
			.param("user", userId)
			.param("role", role)
			.update();
	}

	/**
	 * A personal API token, as {@code POST /tokens} stores it.
	 * @param userId whose it is
	 * @param workspaceId the one workspace it is limited to, if any
	 * @param scope {@code read} or {@code write}
	 * @return the token's secret
	 */
	protected String token(String userId, @Nullable String workspaceId, String scope) {
		String secret = Secrets.newSecret(Secrets.TOKEN_PREFIX);
		this.jdbc.sql("""
				INSERT INTO api_tokens (id, user_id, workspace_id, name, prefix, token_hash, scope, created_at)
				VALUES (:id, :user, :workspace, 'test', :prefix, :hash, :scope, now())""")
			.param("id", newId())
			.param("user", userId)
			.param("workspace", workspaceId)
			.param("prefix", secret.substring(0, Secrets.TOKEN_PREFIX.length() + 4))
			.param("hash", Secrets.sha256Hex(secret))
			.param("scope", scope)
			.update();
		return secret;
	}

	/**
	 * A request from someone signed in, as the web app sends it.
	 * @param person who sends it
	 * @param method its method
	 * @param uri where to
	 * @param body its JSON body, if any
	 * @return the answer
	 */
	protected MvcTestResult send(SignedIn person, HttpMethod method, String uri, @Nullable String body) {
		MockMvcTester.MockMvcRequestBuilder request = person.browser(this.mvc.method(method).uri(uri));
		return (body != null) ? request.contentType(MediaType.APPLICATION_JSON).content(body).exchange()
				: request.exchange();
	}

	/**
	 * A workspace's history.
	 * @param workspaceId the workspace
	 * @return its events, oldest first
	 */
	protected List<HistoryEvent> history(String workspaceId) {
		return events("workspace_id", workspaceId);
	}

	/**
	 * A project's history.
	 * @param projectId the project
	 * @return its events, oldest first
	 */
	protected List<HistoryEvent> projectHistory(String projectId) {
		return events("project_id", projectId);
	}

	protected static JsonNode created(MvcTestResult result) {
		assertThat(result).hasStatus(201);
		return json(result);
	}

	protected static JsonNode json(MvcTestResult result) {
		return JSON.readTree(result.getResponse().getContentAsByteArray());
	}

	protected static JsonNode json(String text) {
		return JSON.readTree(text);
	}

	/**
	 * A parameter as the OpenAPI document describes it.
	 * @param path the endpoint's path, such as {@code /api/v1/projects/{projectId}}
	 * @param method its method, lower case
	 * @param name the parameter's name
	 * @return the parameter's description
	 */
	protected JsonNode documentedParameter(String path, String method, String name) {
		JsonNode document = json(this.mvc.get().uri("/api/v1/openapi.json").exchange());
		return document.path("paths")
			.path(path)
			.path(method)
			.path("parameters")
			.valueStream()
			.filter((parameter) -> name.equals(parameter.path("name").asString()))
			.findFirst()
			.orElseGet(() -> fail(path + " documents no " + name));
	}

	/**
	 * The ids of a list's items, not of what they hold.
	 * @param items a JSON array of objects with an {@code id}
	 * @return the ids, in order
	 */
	protected static List<String> ids(JsonNode items) {
		return items.valueStream().map((item) -> item.path("id").asString()).toList();
	}

	protected static String newId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private List<HistoryEvent> events(String column, String id) {
		return this.jdbc
			.sql("SELECT action, target_kind, target_id, meta::text AS meta, project_id FROM audit_events WHERE "
					+ column + " = :id ORDER BY id")
			.param("id", id)
			.query((rows, number) -> {
				String meta = rows.getString("meta");
				return new HistoryEvent(rows.getString("action"), rows.getString("target_kind"),
						rows.getString("target_id"), (meta != null) ? JSON.readTree(meta) : null,
						rows.getString("project_id"));
			})
			.list();
	}

	/**
	 * Someone signed in with a browser session.
	 *
	 * @param userId the person
	 * @param workspaceId their personal workspace
	 * @param cookie the session cookie's value
	 * @param csrf the session's CSRF token
	 */
	public record SignedIn(String userId, String workspaceId, String cookie, String csrf) {

		/**
		 * A request as the web app sends it: with the session cookie and the CSRF header.
		 * @param <B> the request builder's type
		 * @param request the request
		 * @return the request, signed in
		 */
		public <B extends AbstractMockHttpServletRequestBuilder<B>> B browser(B request) {
			return cookie(request).header(Csrf.HEADER, this.csrf);
		}

		/**
		 * A request that carries the session cookie but no CSRF header, as another site
		 * can make a browser send.
		 * @param <B> the request builder's type
		 * @param request the request
		 * @return the request, with the cookie
		 */
		public <B extends AbstractMockHttpServletRequestBuilder<B>> B cookie(B request) {
			return request.cookie(new Cookie(Principal.SESSION_COOKIE, this.cookie));
		}

	}

}
