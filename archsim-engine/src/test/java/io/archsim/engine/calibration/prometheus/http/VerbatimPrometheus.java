package io.archsim.engine.calibration.prometheus.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLContext;

import io.archsim.engine.calibration.prometheus.http.StubPrometheus.Request;

/**
 * A stand-in Prometheus on 127.0.0.1 that reads each request as the client wrote it,
 * below any HTTP server's parsing, and answers it with a range query's success. It takes
 * the requests {@link StubPrometheus} cannot: since Java 25.0.5 the JDK's HttpServer
 * refuses a request target that starts with {@code //} (400, "Bad request URI") before
 * its handler sees it, and a path that looks like a host is what the outbound tests send
 * on purpose. ({@link RawPrometheus} writes the answers a server never sends; this one
 * reads the requests.)
 */
final class VerbatimPrometheus implements AutoCloseable {

	private static final int BACKLOG = 50;

	/** How long a connection may take to send its request: the client's own timeout. */
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

	private final ServerSocket socket;

	private final String scheme;

	private final List<Request> requests = new CopyOnWriteArrayList<>();

	private VerbatimPrometheus(ServerSocket socket, String scheme) {
		this.socket = socket;
		this.scheme = scheme;
		Thread.ofVirtual().start(this::serve);
	}

	/**
	 * A server over plain HTTP.
	 * @return the started server
	 */
	static VerbatimPrometheus plain() {
		return listening(ServerSocketFactory.getDefault(), "http");
	}

	/**
	 * A server over HTTPS.
	 * @param tls its certificate
	 * @return the started server
	 */
	static VerbatimPrometheus tls(SSLContext tls) {
		return listening(tls.getServerSocketFactory(), "https");
	}

	private static VerbatimPrometheus listening(ServerSocketFactory sockets, String scheme) {
		try {
			return new VerbatimPrometheus(sockets.createServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress()),
					scheme);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// each connection in turn: its request read, kept and answered, then closed
	private void serve() {
		while (!this.socket.isClosed()) {
			try (Socket client = this.socket.accept()) {
				client.setSoTimeout(Math.toIntExact(READ_TIMEOUT.toMillis()));
				Optional<Request> request = read(client);
				if (request.isPresent()) {
					this.requests.add(request.get());
					answerSuccess(client.getOutputStream());
				}
			}
			catch (IOException ex) {
				// closed, which ends the loop; or a client sent no request, failed its
				// handshake
				// or left before its answer
			}
		}
	}

	// the request line and headers as the client wrote them; empty when it sent none
	private static Optional<Request> read(Socket client) throws IOException {
		BufferedReader in = new BufferedReader(
				new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
		String requestLine = in.readLine();
		if (requestLine == null) {
			return Optional.empty();
		}
		Map<String, String> headers = new HashMap<>();
		for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
			int colon = line.indexOf(':');
			if (colon > 0) {
				headers.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT),
						line.substring(colon + 1).strip());
			}
		}
		// method, target and version, one space apart
		String target = requestLine.substring(requestLine.indexOf(' ') + 1, requestLine.lastIndexOf(' '));
		return Optional
			.of(new Request(target, String.valueOf(headers.get("host")), String.valueOf(headers.get("accept")),
					String.valueOf(headers.get("accept-encoding")), client.getPort()));
	}

	// a range query's success, its length announced, and the connection closing after it
	private static void answerSuccess(OutputStream out) throws IOException {
		byte[] body = StubPrometheus.SUCCESS.getBytes(StandardCharsets.UTF_8);
		out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length
				+ "\r\nConnection: close\r\n\r\n")
			.getBytes(StandardCharsets.US_ASCII));
		out.write(body);
		out.flush();
	}

	/**
	 * Its port.
	 * @return the port
	 */
	int port() {
		return this.socket.getLocalPort();
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
	 * What it was sent, in order: each request's target as written on its request line.
	 * @return the requests
	 */
	List<Request> requests() {
		return List.copyOf(this.requests);
	}

	@Override
	public void close() {
		try {
			this.socket.close();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
