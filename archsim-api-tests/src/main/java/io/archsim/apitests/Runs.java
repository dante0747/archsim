package io.archsim.apitests;

import java.net.HttpURLConnection;
import java.time.Duration;

import tools.jackson.databind.JsonNode;

/** Steps most tests take: making a project, waiting for a run. */
public final class Runs {

	/** How long {@link #waitFor(ApiClient, String)} waits for a run to finish. */
	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

	/** How long to wait between two looks at a run. */
	private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

	private Runs() {
	}

	/**
	 * Creates a project ({@code POST /projects}); fails the test unless it answers 201.
	 * @param client the client that creates it
	 * @param document the project's design
	 * @return the new project
	 */
	public static JsonNode createProject(ApiClient client, JsonNode document) {
		return client.post("/projects", Json.object("document", document))
			.expectStatus(HttpURLConnection.HTTP_CREATED)
			.json();
	}

	/**
	 * Creates a project of the starter design (8 s).
	 * @param client the client that creates it
	 * @return the new project
	 */
	public static JsonNode createProject(ApiClient client) {
		return createProject(client, Examples.starter());
	}

	/**
	 * Polls a run until it is neither queued nor running (60 s at most).
	 * @param client the client that polls
	 * @param runId the run's id
	 * @return the finished run
	 */
	public static JsonNode waitFor(ApiClient client, String runId) {
		return waitFor(client, runId, DEFAULT_TIMEOUT);
	}

	/**
	 * Polls a run until it is neither queued nor running; fails the test when it is still
	 * either after {@code timeout}.
	 * @param client the client that polls
	 * @param runId the run's id
	 * @param timeout how long to wait at most
	 * @return the finished run
	 */
	public static JsonNode waitFor(ApiClient client, String runId, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			JsonNode run = client.get("/runs/" + runId).expectStatus(HttpURLConnection.HTTP_OK).json();
			String status = run.path("status").asString();
			if (!"queued".equals(status) && !"running".equals(status)) {
				return run;
			}
			pause(POLL_INTERVAL);
		}
		throw new AssertionError("run " + runId + " did not finish in " + timeout);
	}

	/**
	 * Sleeps; a test that waits for the server has nothing better to do.
	 * @param duration how long to sleep
	 */
	public static void pause(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
