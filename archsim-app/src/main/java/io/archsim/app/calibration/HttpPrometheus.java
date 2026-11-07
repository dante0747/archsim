package io.archsim.app.calibration;

import javax.net.ssl.SSLContext;

import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.ssl.SSLContexts;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.calibration.prometheus.Matrices;
import io.archsim.engine.calibration.prometheus.PrometheusClient;
import io.archsim.engine.calibration.prometheus.PrometheusException;

/**
 * Prometheus range queries over HTTP ({@code GET <url>/api/v1/query_range}), as the
 * Python engine's {@code HttpPrometheus} makes them, while a calibration runs.
 *
 * <p>
 * Each query is bounded. One deadline covers connecting, the headers and the whole body,
 * so a server that trickles bytes cannot hold it longer; looking the host up counts
 * against it too, though the lookup itself is not cut short, as in Python. An answer over
 * the size cap is refused, by its announced length or while it is read. Redirects are not
 * followed, nothing is retried, decompressed or sent through a proxy, and a host off the
 * allowlist is refused before anything is sent.
 *
 * <p>
 * The host is looked up once per query, and the connection goes to that address while TLS
 * is checked against the name: a name cannot answer one address for the check and another
 * for the call. HttpClient itself looks no name up. Under {@code requireGlobal} (a hosted
 * server), every address the name has must be public; an address the allowlist itself
 * names is trusted as written. Errors never repeat what the server said:
 * {@link PrometheusException#detail()} keeps it.
 */
final class HttpPrometheus implements PrometheusClient, AutoCloseable {

	private static final int OK = 200;

	private final PrometheusServer server;

	private final PinnedHosts hosts;

	private final PrometheusExchange exchange;

	/**
	 * A client of a server.
	 * @param server the server and how it is called
	 * @param tls the certificates trusted for HTTPS
	 * @param resolver how its name is looked up
	 */
	HttpPrometheus(PrometheusServer server, SSLContext tls, HostResolver resolver) {
		this.server = server;
		this.hosts = new PinnedHosts(server, resolver);
		this.exchange = new PrometheusExchange(server, tls);
	}

	/**
	 * A client of a server, trusting the system's certificates and looking names up as
	 * the system does.
	 * @param server the server and how it is called
	 * @return the client
	 */
	static HttpPrometheus of(PrometheusServer server) {
		return new HttpPrometheus(server, SSLContexts.createSystemDefault(), HostResolver.SYSTEM);
	}

	@Override
	public String source() {
		return "Prometheus " + this.server.url();
	}

	@Override
	public JsonNode queryRange(String query, double start, double end, double step) {
		String url = this.server.url();
		PrometheusUrl parts = PrometheusUrl.split(url);
		int port = parts.portOrDefault();
		String host = parts.hostname();
		if (!parts.isHttp() || host == null) {
			throw new PrometheusException("the Prometheus URL must be http(s)://host[:port]");
		}
		if (!PrometheusUrl.allowed(url, this.server.allowedHosts())) {
			throw new PrometheusException(
					host + " is not an allowed Prometheus host (ARCHSIM_PROMETHEUS_ALLOWED_HOSTS)");
		}
		String target = QueryTargets.of(url, parts.path(), query, new QueryTargets.Range(start, end, step));
		Deadline deadline = Deadline.after(this.server.timeoutSeconds());
		HttpHost pinned = this.hosts.pin(parts.scheme(), host, port);
		PrometheusExchange.Answer answer = this.exchange.get(pinned, target, deadline);
		JsonNode body = JsonAnswers.read(answer.body());
		if (body == null || !body.isObject()) {
			throw new PrometheusException(
					url + " did not answer like a Prometheus server (HTTP " + answer.status() + ")");
		}
		if (answer.status() != OK && "success".equals(body.path("status").asString(null))) {
			throw new PrometheusException(url + " answered HTTP " + answer.status() + ": " + query);
		}
		return Matrices.of(body, query);
	}

	@Override
	public void close() {
		this.exchange.close();
	}

}
