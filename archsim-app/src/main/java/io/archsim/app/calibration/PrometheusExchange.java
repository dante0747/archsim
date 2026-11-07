package io.archsim.app.calibration;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.URIAuthority;
import org.apache.hc.core5.util.Timeout;
import org.jspecify.annotations.Nullable;

/**
 * One GET of a Prometheus query, under its deadline and the size cap, on a connection
 * that is never reused: it is dropped at once when the query ends, unread bytes and all.
 */
final class PrometheusExchange implements AutoCloseable {

	/** What a request starts as, before its scheme, authority and path are set. */
	private static final URI ROOT = URI.create("/");

	private final PrometheusServer server;

	private final CloseableHttpClient http;

	/**
	 * The exchanges with a server.
	 * @param server the server, its deadline and its size cap
	 * @param tls the certificates trusted for HTTPS
	 */
	PrometheusExchange(PrometheusServer server, SSLContext tls) {
		this.server = server;
		long millis = Math.round(server.timeoutSeconds() * TimeUnit.SECONDS.toMillis(1));
		this.http = PinnedClients.create(Timeout.ofMilliseconds(millis), tls);
	}

	/**
	 * Sends a GET and reads the answer.
	 * @param target where it goes, its address pinned
	 * @param path the request's target, its query included, sent as written
	 * @param deadline when it must end
	 * @return the status and the body
	 * @throws io.archsim.engine.calibration.prometheus.PrometheusException when the
	 * server cannot be called, answers too late, or sends too much
	 */
	Answer get(HttpHost target, String path, Deadline deadline) {
		HttpGet request = new HttpGet(ROOT);
		// the host is the pinned target's, never one a path starting with // would name
		request.setScheme(target.getSchemeName());
		request.setAuthority(new URIAuthority(target));
		request.setPath(path);
		request.setHeader(HttpHeaders.ACCEPT, "application/json");
		// as Python's http.client asks: the body as it is, which is all that is read
		request.setHeader(HttpHeaders.ACCEPT_ENCODING, "identity");
		ClassicHttpResponse response = null;
		try (Deadline.Watch watch = deadline.watch(request)) {
			response = this.http.executeOpen(target, request, null);
			return new Answer(response.getCode(), BoundedBodies.read(response, this.server, watch.deadline()));
		}
		catch (IOException ex) {
			throw QueryFailures.failed(this.server, ex, deadline.passed());
		}
		finally {
			request.cancel();
			closeDropped(response);
		}
	}

	@Override
	public void close() {
		this.http.close(CloseMode.IMMEDIATE);
	}

	/**
	 * Closes an answer whose connection is already dropped: closing it can only fail now,
	 * and nothing is lost when it does.
	 * @param response the answer; {@code null} for none
	 */
	private static void closeDropped(@Nullable ClassicHttpResponse response) {
		if (response == null) {
			return;
		}
		try {
			response.close();
		}
		catch (IOException ex) {
			// the connection is gone already
		}
	}

	/**
	 * A status and a body.
	 */
	static final class Answer {

		private final int status;

		private final byte[] body;

		Answer(int status, byte[] body) {
			this.status = status;
			this.body = body;
		}

		int status() {
			return this.status;
		}

		byte[] body() {
			return this.body;
		}

	}

}
