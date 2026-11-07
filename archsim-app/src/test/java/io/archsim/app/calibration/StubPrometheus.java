package io.archsim.app.calibration;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * A stand-in Prometheus on 127.0.0.1 that answers every request with one status and body,
 * and remembers what it was sent.
 */
final class StubPrometheus implements AutoCloseable {

	/** A range query's success with no series. */
	static final String SUCCESS = "{\"status\": \"success\", \"data\": {\"resultType\": \"matrix\", \"result\": []}}";

	private final HttpServer server;

	private final String scheme;

	private final List<Request> requests = new CopyOnWriteArrayList<>();

	private StubPrometheus(HttpServer server, String scheme, Answer answer) {
		this.server = server;
		this.scheme = scheme;
		server.createContext("/", (exchange) -> {
			this.requests.add(new Request(exchange.getRequestURI().toString(),
					String.valueOf(exchange.getRequestHeaders().getFirst("Host")),
					String.valueOf(exchange.getRequestHeaders().getFirst("Accept")),
					String.valueOf(exchange.getRequestHeaders().getFirst("Accept-Encoding")),
					exchange.getRemoteAddress().getPort()));
			answer.write(exchange);
		});
		server.start();
	}

	/**
	 * A server that answers every request with a status and a body, its length announced.
	 * @param status the status
	 * @param body the body
	 * @return the started server
	 */
	static StubPrometheus answering(int status, String body) {
		return handling((exchange) -> send(exchange, status, body, true));
	}

	/**
	 * A server that answers every request as told.
	 * @param answer how it answers
	 * @return the started server
	 */
	static StubPrometheus handling(Answer answer) {
		try {
			return new StubPrometheus(HttpServer.create(loopback(), 0), "http", answer);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * An HTTPS server that answers every request with a range query's success.
	 * @param tls its certificate
	 * @return the started server
	 */
	static StubPrometheus tls(SSLContext tls) {
		try {
			HttpsServer server = HttpsServer.create(loopback(), 0);
			server.setHttpsConfigurator(new HttpsConfigurator(tls));
			return new StubPrometheus(server, "https", (exchange) -> send(exchange, 200, SUCCESS, true));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Answers an exchange.
	 * @param exchange the exchange
	 * @param status the status
	 * @param body the body
	 * @param announced whether the body's length is announced; else it is sent chunked
	 * @throws IOException when the client is gone
	 */
	static void send(HttpExchange exchange, int status, String body, boolean announced) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, (bytes.length == 0) ? -1 : (announced ? bytes.length : 0));
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static InetSocketAddress loopback() {
		return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
	}

	/**
	 * Its port.
	 * @return the port
	 */
	int port() {
		return this.server.getAddress().getPort();
	}

	/**
	 * Its address, by its IP address.
	 * @return {@code http://127.0.0.1:<port>}, or {@code https://…}
	 */
	String url() {
		return url("127.0.0.1");
	}

	/**
	 * Its address, by a name.
	 * @param host the name
	 * @return {@code http://<host>:<port>}, or {@code https://…}
	 */
	String url(String host) {
		return this.scheme + "://" + host + ":" + port();
	}

	/**
	 * What it was sent, in order.
	 * @return the requests
	 */
	List<Request> requests() {
		return List.copyOf(this.requests);
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

	/**
	 * How the server answers a request.
	 */
	@FunctionalInterface
	interface Answer {

		/**
		 * Answers a request.
		 * @param exchange the request and its answer
		 * @throws IOException when the client is gone
		 */
		void write(HttpExchange exchange) throws IOException;

	}

	/**
	 * A request the server was sent.
	 *
	 * @param uri the request's target, its query included
	 * @param host its {@code Host} header
	 * @param accept its {@code Accept} header
	 * @param acceptEncoding its {@code Accept-Encoding} header
	 * @param clientPort the port it came from, one per connection
	 */
	record Request(String uri, String host, String accept, String acceptEncoding, int clientPort) {

	}

}
