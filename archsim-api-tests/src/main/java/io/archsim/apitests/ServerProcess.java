package io.archsim.apitests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

/**
 * An API server the suite started: its process (and children), address, database and log.
 */
public final class ServerProcess {

	/** How long a health check waits to connect. */
	private static final Duration HEALTH_CONNECT_TIMEOUT = Duration.ofSeconds(2);

	/** How long a health check waits for its answer. */
	private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(5);

	/** How long to wait between health checks. */
	private static final Duration HEALTH_POLL_INTERVAL = Duration.ofMillis(250);

	/** How long a stopped server may take to exit before it is killed, in seconds. */
	private static final long STOP_TIMEOUT_SECONDS = 10;

	/** How many of the log's last lines a failure shows. */
	private static final int LOG_TAIL_LINES = 40;

	private final String name;

	private final Process process;

	private final Urls urls;

	private final Path log;

	/**
	 * Describes a server the suite started.
	 * @param name the server as messages name it
	 * @param process the server's process
	 * @param urls where the server answers and where its database is
	 * @param log the file the server logs to
	 */
	ServerProcess(String name, Process process, Urls urls, Path log) {
		this.name = name;
		this.process = process;
		this.urls = urls;
		this.log = log;
	}

	public URI url() {
		return this.urls.api();
	}

	public String jdbcUrl() {
		return this.urls.jdbc();
	}

	/**
	 * Waits until the server's health check answers 200; fails, with the log's end, if
	 * not.
	 * @param health what answers 200 once the server is ready
	 * @param timeout how long to wait
	 */
	void awaitHealthy(URI health, Duration timeout) {
		HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(HEALTH_CONNECT_TIMEOUT)
			.build();
		HttpRequest check = HttpRequest.newBuilder(health).timeout(HEALTH_TIMEOUT).build();
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (!this.process.isAlive()) {
				throw new IllegalStateException(this.name + " stopped (exit " + this.process.exitValue()
						+ ") before it was healthy:\n" + logTail());
			}
			if (isHealthy(http, check)) {
				return;
			}
			Runs.pause(HEALTH_POLL_INTERVAL);
		}
		stop();
		throw new IllegalStateException(this.name + " was not healthy within " + timeout + ":\n" + logTail());
	}

	/**
	 * Waits until the process logs a line, for a process that answers no HTTP (a worker);
	 * fails, with the log's end, if not.
	 * @param ready what the log says once the process is ready
	 * @param timeout how long to wait
	 */
	void awaitLogged(String ready, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (!this.process.isAlive()) {
				throw new IllegalStateException(this.name + " stopped (exit " + this.process.exitValue()
						+ ") before it was ready:\n" + logTail());
			}
			if (log().contains(ready)) {
				return;
			}
			Runs.pause(HEALTH_POLL_INTERVAL);
		}
		stop();
		throw new IllegalStateException(this.name + " was not ready within " + timeout + ":\n" + logTail());
	}

	/** Stops the server and every process it started (simulation processes included). */
	void stop() {
		List<ProcessHandle> children = this.process.descendants().toList();
		this.process.destroy();
		children.forEach(ProcessHandle::destroy);
		try {
			if (!this.process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				this.process.destroyForcibly();
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
	}

	private static boolean isHealthy(HttpClient http, HttpRequest health) {
		try {
			return http.send(health, BodyHandlers.discarding()).statusCode() == HttpURLConnection.HTTP_OK;
		}
		catch (IOException ex) {
			// not listening yet
			return false;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private String log() {
		try {
			return Files.readString(this.log, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private String logTail() {
		try {
			List<String> lines = Files.readAllLines(this.log, StandardCharsets.UTF_8);
			return String.join("\n", lines.subList(Math.max(0, lines.size() - LOG_TAIL_LINES), lines.size()));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Where a started server answers, where its database is, and how to tell it is ready.
	 *
	 * @param api the server's base URL
	 * @param jdbc the JDBC URL of the server's database
	 * @param health what answers 200 once the server is ready; none for a worker, which
	 * answers no HTTP
	 */
	record Urls(URI api, String jdbc, @Nullable URI health) {
	}

}
