package io.archsim.apitests;

import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The steps {@link SharesTests} builds its shares from: a design whose calibration names
 * a Prometheus address with credentials, its projects and runs, a token client, and a
 * wait for room in the public page's one-minute window.
 */
final class Shares {

	/** The calibration source of {@link #calibrated()}: an address with credentials. */
	static final String PROMETHEUS = "Prometheus https://scraper:hunter2@prom.internal.example:9090";

	private Shares() {
	}

	/**
	 * A short starter design whose calibration names a Prometheus address with
	 * credentials, as the Python tests' stored runs have: the engine copies it into the
	 * report's assumptions.
	 */
	static ObjectNode calibrated() {
		ObjectNode doc = Examples.starter(4, 1);
		doc.set("calibration", Json.object("at", "2026-10-01T00:00:00Z", "source", PROMETHEUS, "inputs", 2));
		return doc;
	}

	/** A project of {@link #calibrated()} in the client's workspace; returns its id. */
	static String calibratedProject(ApiClient client) {
		return id(Runs.createProject(client, calibrated()));
	}

	/** A run of the project's saved design, simulated to the end; returns its id. */
	static String finishedRun(ApiClient client, String projectId) {
		ApiResponse res = client.post("/projects/" + projectId + "/runs", Json.object());
		assertThat(res.status()).as(res.text()).isEqualTo(202);
		JsonNode done = Runs.waitFor(client, id(res.json()));
		assertThat(done.path("status").asString()).as(done.toString()).isEqualTo("succeeded");
		return id(done);
	}

	/**
	 * A run of 120 simulated seconds, still queued or running when this returns; its id.
	 */
	static String startLongRun(ApiClient client, String projectId) {
		ApiResponse res = client.post("/projects/" + projectId + "/runs",
				Json.object("document", Examples.starter(120, 5)));
		assertThat(res.status()).as(res.text()).isEqualTo(202);
		return id(res.json());
	}

	/**
	 * A client that sends {@code person}'s new API token of {@code scope} (read or
	 * write).
	 */
	static ApiClient tokenClient(ApiServer server, ApiClient person, String scope) {
		ApiResponse res = person.post("/tokens", Json.object("name", "test " + scope, "scope", scope));
		assertThat(res.status()).as(res.text()).isEqualTo(201);
		return server.client().header("Authorization", "Bearer " + res.json().path("token").asString());
	}

	/**
	 * Waits for the next minute when less than 20 s of this one are left: the limit
	 * counts views per address in windows of a minute, and the views of a test must fall
	 * in one.
	 */
	static void awaitRoomInTheMinute() {
		long intoTheMinute = System.currentTimeMillis() % 60_000;
		if (intoTheMinute > 40_000) {
			Runs.pause(Duration.ofMillis(60_000 - intoTheMinute + 250));
		}
	}

	/** The calibration source a report's assumptions name. */
	static String calibrationSource(JsonNode report) {
		return report.path("assumptions").path("calibration").path("source").asString();
	}

	private static String id(JsonNode node) {
		return node.path("id").asString();
	}

}
