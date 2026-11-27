package io.archsim.app.limits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The writes that charge a rate limit, many at once against a pool of two connections:
 * each request holds one connection at a time, so they take turns and every one is
 * served. A write that charged its limit from inside its own transaction held one
 * connection and waited for a second (ADR-0105).
 */
@TestPropertySource(properties = { "archsim.rate-limits=true", "spring.datasource.hikari.maximum-pool-size=2",
		"spring.datasource.hikari.connection-timeout=3000" })
class ChargedWritesTests extends HostedTestSupport {

	private static final int AT_ONCE = 6;

	private static final int CREATED = 201;

	@Test
	void comment_manyAtOnceOnTwoConnections_isEachWritten() throws Exception {
		SignedIn author = signedIn("Ada");
		String project = project(author);

		List<Integer> statuses = atOnce(() -> send(author, HttpMethod.POST, "/api/v1/projects/" + project + "/comments",
				"{\"body\": \"Looks right.\"}"));

		assertThat(statuses).containsOnly(CREATED);
	}

	@Test
	void signoff_manyAtOnceOnTwoConnections_isEachWritten() throws Exception {
		SignedIn reviewer = signedIn("Ada");
		String run = succeededRun(project(reviewer));

		List<Integer> statuses = atOnce(() -> send(reviewer, HttpMethod.POST, "/api/v1/runs/" + run + "/signoffs",
				"{\"decision\": \"approved\"}"));

		assertThat(statuses).containsOnly(CREATED);
	}

	@Test
	void share_manyAtOnceOnTwoConnections_isEachMade() throws Exception {
		SignedIn owner = signedIn("Ada");
		String run = succeededRun(project(owner));

		List<Integer> statuses = atOnce(
				() -> send(owner, HttpMethod.POST, "/api/v1/runs/" + run + "/shares", "{\"title\": \"Peak\"}"));

		assertThat(statuses).containsOnly(CREATED);
	}

	@Test
	void invitation_manyAtOnceOnTwoConnections_isEachIssued() throws Exception {
		SignedIn owner = signedIn("Ada");
		String team = created(send(owner, HttpMethod.POST, "/api/v1/workspaces", "{\"name\": \"Team\"}")).path("id")
			.asString();

		List<Integer> statuses = atOnce(() -> send(owner, HttpMethod.POST,
				"/api/v1/workspaces/" + team + "/invitations", "{\"role\": \"editor\"}"));

		assertThat(statuses).containsOnly(CREATED);
	}

	@Test
	void workspace_manyAtOnceOnTwoConnections_isEachMade() throws Exception {
		SignedIn owner = signedIn("Ada");

		List<Integer> statuses = atOnce(
				() -> send(owner, HttpMethod.POST, "/api/v1/workspaces", "{\"name\": \"Team\"}"));

		assertThat(statuses).containsOnly(CREATED);
	}

	/**
	 * The statuses of a request sent {@link #AT_ONCE} times at once, each on a thread of
	 * its own, as the server's virtual threads take them. No request is sent before every
	 * thread is ready to send its own.
	 */
	private static List<Integer> atOnce(Supplier<MvcTestResult> request)
			throws InterruptedException, ExecutionException {
		CountDownLatch ready = new CountDownLatch(AT_ONCE);
		Callable<Integer> status = () -> {
			ready.countDown();
			ready.await();
			return request.get().getResponse().getStatus();
		};
		List<Callable<Integer>> requests = new ArrayList<>(Collections.nCopies(AT_ONCE, status));
		List<Integer> statuses = new ArrayList<>();
		try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
			for (Future<Integer> answered : threads.invokeAll(requests)) {
				statuses.add(answered.get());
			}
		}
		return statuses;
	}

	private String project(SignedIn owner) {
		String id = newId();
		jdbc().sql("""
				INSERT INTO projects (id, workspace_id, created_by, name, document, schema_version, revision,
				                      created_at, updated_at)
				VALUES (:id, :workspace, :user, 'Shop', CAST(:document AS jsonb), '1.7', 1, now(), now())""")
			.param("id", id)
			.param("workspace", owner.workspaceId())
			.param("user", owner.userId())
			.param("document", """
					{"metadata": {"name": "Shop"}, "architecture": {"nodes": [{"id": "api"}], "edges": []}}""")
			.update();
		return id;
	}

	private String succeededRun(String projectId) {
		String id = newId();
		jdbc().sql("""
				INSERT INTO simulation_runs (id, project_id, workspace_id, label, status, progress, seed,
				                             engine_version, input_digest, result_digest, input_document, report,
				                             created_at)
				SELECT :id, p.id, p.workspace_id, 'Baseline', 'succeeded', 1, 7, '1.6.0', 'in-digest', 'out-digest',
				       p.document, CAST(:report AS jsonb), now()
				FROM projects p WHERE p.id = :project""")
			.param("id", id)
			.param("report", "{\"reportVersion\": \"1.21\", \"summary\": {\"latencyP95Ms\": 120}}")
			.param("project", projectId)
			.update();
		return id;
	}

}
