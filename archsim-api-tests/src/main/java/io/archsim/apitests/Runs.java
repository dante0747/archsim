package io.archsim.apitests;

import java.time.Duration;
import tools.jackson.databind.JsonNode;

/** Steps most tests take: making a project, waiting for a run. */
public final class Runs {

	private Runs() {
	}

	/**
	 * {@code POST /projects} with {@code document}; fails the test unless it answers 201.
	 */
	public static JsonNode createProject(ApiClient client, JsonNode document) {
		ApiResponse res = client.post("/projects", Json.object("document", document));
		if (res.status() != 201) {
			throw new AssertionError(res + ": " + res.text());
		}
		return res.json();
	}

	/** A project of the starter design (8 s). */
	public static JsonNode createProject(ApiClient client) {
		return createProject(client, Examples.starter());
	}

	/** Polls a run until it is neither queued nor running (60 s at most); returns it. */
	public static JsonNode waitFor(ApiClient client, String runId) {
		return waitFor(client, runId, Duration.ofSeconds(60));
	}

	public static JsonNode waitFor(ApiClient client, String runId, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			ApiResponse res = client.get("/runs/" + runId);
			if (res.status() != 200) {
				throw new AssertionError(res + ": " + res.text());
			}
			String status = res.json().path("status").asString();
			if (!status.equals("queued") && !status.equals("running")) {
				return res.json();
			}
			pause(Duration.ofMillis(50));
		}
		throw new AssertionError("run " + runId + " did not finish in " + timeout);
	}

	/** Sleeps; a test that waits for the server has nothing better to do. */
	public static void pause(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

}
