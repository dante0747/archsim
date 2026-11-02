package io.archsim.apitests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** An API server the suite started: its process (and children), address, database and log. */
public final class ServerProcess {
    private final String name;
    private final Process process;
    private final URI url;
    private final String jdbcUrl;
    private final Path log;

    ServerProcess(String name, Process process, URI url, String jdbcUrl, Path log) {
        this.name = name;
        this.process = process;
        this.url = url;
        this.jdbcUrl = jdbcUrl;
        this.log = log;
    }

    public URI url() {
        return url;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    /** Waits until {@code GET /api/v1/health} answers 200; fails, with the log's end, if not. */
    void awaitHealthy(Duration timeout) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest health = HttpRequest.newBuilder(url.resolve(ApiClient.API + "/health"))
                .timeout(Duration.ofSeconds(5))
                .build();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                        name + " stopped (exit " + process.exitValue() + ") before it was healthy:\n" + logTail());
            }
            try {
                if (http.send(health, BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // not listening yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            Runs.pause(Duration.ofMillis(250));
        }
        stop();
        throw new IllegalStateException(name + " was not healthy within " + timeout + ":\n" + logTail());
    }

    /** Stops the server and every process it started (simulation processes included). */
    void stop() {
        List<ProcessHandle> children = process.descendants().toList();
        process.destroy();
        children.forEach(ProcessHandle::destroy);
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    private String logTail() {
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
