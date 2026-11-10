package io.archsim.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Prometheus a recorded calibration queried, as {@code scripts/export_cli_cases.py}'s
 * stub behaved: {@code recorded} answers each query with its response in the case's
 * recorded responses, {@code badData} refuses every query as Prometheus refuses bad
 * PromQL, {@code html} answers as a gateway's error page does, and {@code closed} is a
 * port nothing listens on.
 */
final class PrometheusStub implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final int OK = 200;

	private static final int BAD_REQUEST = 400;

	private static final String BAD_DATA = "{\"status\": \"error\", \"errorType\": \"bad_data\", "
			+ "\"error\": \"parse error at char 7\"}";

	private final @Nullable HttpServer server;

	private final String url;

	private PrometheusStub(@Nullable HttpServer server, String url) {
		this.server = server;
		this.url = url;
	}

	/**
	 * The server a recorded case asks for, started.
	 * @param recorded the case, whose {@code server} says how the server behaves
	 * @return the server; one without an address when the case asks for none
	 * @throws IOException when it cannot listen
	 */
	static PrometheusStub behaving(JsonNode recorded) throws IOException {
		String behaviour = recorded.path("server").stringValue("");
		InetAddress loopback = InetAddress.getLoopbackAddress();
		if (behaviour.isEmpty()) {
			return new PrometheusStub(null, "");
		}
		if ("closed".equals(behaviour)) {
			try (ServerSocket probe = new ServerSocket(0, 1, loopback)) {
				return new PrometheusStub(null, "http://127.0.0.1:" + probe.getLocalPort());
			}
		}
		JsonNode responses = JSON.readTree(recorded.at("/inputs/{recorded}").stringValue()).path("responses");
		HttpServer server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
		server.createContext("/", (exchange) -> answer(exchange, behaviour, responses));
		server.start();
		return new PrometheusStub(server, "http://127.0.0.1:" + server.getAddress().getPort());
	}

	/**
	 * The server's base URL.
	 * @return the URL; empty when the case asks for no server
	 */
	String url() {
		return this.url;
	}

	@Override
	public void close() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	private static void answer(HttpExchange exchange, String behaviour, JsonNode responses) throws IOException {
		String query = query(String.valueOf(exchange.getRequestURI().getRawQuery()));
		if ("html".equals(behaviour)) {
			send(exchange, OK, "text/html", "<html><body>Bad gateway</body></html>");
		}
		else if ("recorded".equals(behaviour) && responses.has(query)) {
			send(exchange, OK, "application/json", responses.get(query).toString());
		}
		else {
			send(exchange, BAD_REQUEST, "application/json", BAD_DATA);
		}
	}

	private static String query(String rawQuery) {
		return Arrays.stream(rawQuery.split("&"))
			.filter((parameter) -> parameter.startsWith("query="))
			.map((parameter) -> URLDecoder.decode(parameter.substring("query=".length()), StandardCharsets.UTF_8))
			.findFirst()
			.orElse("");
	}

	private static void send(HttpExchange exchange, int status, String kind, String body) throws IOException {
		byte[] data = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", kind);
		exchange.sendResponseHeaders(status, data.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(data);
		}
	}

}
