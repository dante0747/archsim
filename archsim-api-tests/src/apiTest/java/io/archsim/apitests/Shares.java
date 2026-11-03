package io.archsim.apitests;

import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The steps {@link SharesTests} builds its shares from: a design whose calibration names
 * a Prometheus address with credentials, its projects and runs, and a wait for room in
 * the public page's one-minute window.
 */
final class Shares {

	/** The calibration source of {@link #calibrated()}: an address with credentials. */
	static final String PROMETHEUS = "Prometheus https://scraper:hunter2@prom.internal.example:9090";

	/** The window in which the public page counts each address's views. */
	private static final Duration VIEW_WINDOW = Duration.ofMinutes(1);

	/** The least time left in a window for a test's views to fall in it. */
	private static final Duration ROOM_FOR_VIEWS = Duration.ofSeconds(20);

	/** How far into the next window to wait, so the views fall clearly inside it. */
	private static final Duration INTO_NEXT_WINDOW = Duration.ofMillis(250);

	private Shares() {
	}

	/**
	 * A short starter design whose calibration names a Prometheus address with
	 * credentials, as the Python tests' stored runs have: the engine copies it into the
	 * report's assumptions.
	 */
	static ObjectNode calibrated() {
		ObjectNode document = Examples.starter(4, 1);
		document.set("calibration", Json.object("at", "2026-10-01T00:00:00Z", "source", PROMETHEUS, "inputs", 2));
		return document;
	}

	/** A project of {@link #calibrated()} in the client's workspace; returns its id. */
	static String calibratedProject(ApiClient client) {
		return Json.id(Runs.createProject(client, calibrated()));
	}

	/**
	 * A run of {@link #calibrated()} simulated to the end, in a new project of the
	 * client's workspace; returns the run's id.
	 */
	static String calibratedRun(ApiClient client) {
		return Json.id(Runs.finishedRun(client, calibratedProject(client)));
	}

	/**
	 * Waits for the next window when less than 20 s of this one are left: the limit
	 * counts views per address in windows of a minute, and the views of a test must fall
	 * in one.
	 */
	static void awaitRoomInTheMinute() {
		Duration intoTheWindow = Duration.ofMillis(System.currentTimeMillis() % VIEW_WINDOW.toMillis());
		Duration leftInTheWindow = VIEW_WINDOW.minus(intoTheWindow);
		if (leftInTheWindow.compareTo(ROOM_FOR_VIEWS) < 0) {
			Runs.pause(leftInTheWindow.plus(INTO_NEXT_WINDOW));
		}
	}

	/** The calibration source a report's assumptions name. */
	static String calibrationSource(JsonNode report) {
		return report.path("assumptions").path("calibration").path("source").asString();
	}

}
