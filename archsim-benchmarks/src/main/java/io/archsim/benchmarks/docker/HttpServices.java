package io.archsim.benchmarks.docker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.text.PythonJsonReader;

/**
 * The services' endpoints over HTTP/1.1, each request given 30 seconds, as {@code urllib}
 * gave the Python harness's.
 */
public final class HttpServices implements ServiceHttp {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	private static final int SUCCESS = 200;

	private static final int REDIRECTION = 300;

	private final HttpClient client = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(TIMEOUT)
		.build();

	@Override
	public JsonNode get(String url) {
		return send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build());
	}

	@Override
	public JsonNode post(String url) {
		return send(HttpRequest.newBuilder(URI.create(url))
			.timeout(TIMEOUT)
			.POST(HttpRequest.BodyPublishers.noBody())
			.build());
	}

	private JsonNode send(HttpRequest request) {
		HttpResponse<byte[]> response = exchange(request);
		if (response.statusCode() < SUCCESS || response.statusCode() >= REDIRECTION) {
			throw new UncheckedIOException(new IOException(request.uri() + " answered " + response.statusCode()));
		}
		return PythonJsonReader.read(new String(response.body(), StandardCharsets.UTF_8));
	}

	private HttpResponse<byte[]> exchange(HttpRequest request) {
		try {
			return this.client.send(request, HttpResponse.BodyHandlers.ofByteArray());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while asking " + request.uri(), ex);
		}
	}

}
