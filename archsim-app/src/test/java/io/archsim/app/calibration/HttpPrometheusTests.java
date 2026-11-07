package io.archsim.app.calibration;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import io.archsim.app.calibration.StubPrometheus.Request;
import io.archsim.engine.calibration.prometheus.PrometheusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The Prometheus client against stand-in servers on 127.0.0.1: one deadline over the
 * headers and the whole body, a size cap, and clean errors that never repeat what the
 * server said. From the Python engine's {@code test_calibration_hardening.py}, with the
 * failures it did not reach.
 */
class HttpPrometheusTests {

	private static final double START = 1_700_000_000.0;

	private static final long MAX_BYTES = 50L * 1024 * 1024;

	private static final String HEAD = String.join("\r\n", "HTTP/1.1 200 OK", "Content-Type: application/json",
			"Content-Length: 40", "", "");

	private static final Duration PAUSE = Duration.ofMillis(200);

	@Test
	void queryRange_trickledBody_endsAtTheDeadline() throws IOException {
		try (RawPrometheus prometheus = RawPrometheus.trickling(HEAD + "x".repeat(40), HEAD.length(), PAUSE)) {
			assertEndsAtTheDeadline(prometheus.url());
		}
	}

	@Test
	void queryRange_trickledHeaders_endAtTheDeadline() throws IOException {
		try (RawPrometheus prometheus = RawPrometheus.trickling(HEAD, 0, PAUSE)) {
			assertEndsAtTheDeadline(prometheus.url());
		}
	}

	@Test
	void queryRange_answerCutOff_saysSo() throws IOException {
		try (RawPrometheus prometheus = RawPrometheus
			.writing("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n{\"status\": ")) {
			assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> query(prometheus.url(), MAX_BYTES))
				.withMessage("cannot query " + prometheus.url() + ": the response was cut off");
		}
	}

	@Test
	void queryRange_connectionClosedBeforeAnAnswer_saysSo() throws IOException {
		try (RawPrometheus prometheus = RawPrometheus.writing("")) {
			assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> query(prometheus.url(), MAX_BYTES))
				.withMessage("cannot query " + prometheus.url() + ": the server closed the connection");
		}
	}

	@Test
	void queryRange_answerThatIsNotHttp_isRefusedWithoutRepeatingIt() throws IOException {
		try (RawPrometheus prometheus = RawPrometheus.writing("SECRET-BANNER of an internal service\r\n\r\n")) {
			assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> query(prometheus.url(), MAX_BYTES))
				.satisfies((ex) -> assertThat(ex.getMessage())
					.startsWith("cannot query " + prometheus.url() + ": not a valid HTTP response (")
					.doesNotContain("SECRET"));
		}
	}

	@Test
	void queryRange_headerLineLongerThanPythonReads_isRefused() throws IOException {
		String head = "HTTP/1.1 200 OK\r\nX-Padding: " + "a".repeat(70_000) + "\r\nContent-Length: 2\r\n\r\n{}";
		try (RawPrometheus prometheus = RawPrometheus.writing(head)) {
			assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> query(prometheus.url(), MAX_BYTES))
				.withMessageStartingWith("cannot query " + prometheus.url() + ": not a valid HTTP response (");
		}
	}

	@Test
	void queryRange_moreHeadersThanPythonReads_isRefused() throws IOException {
		String head = "HTTP/1.1 200 OK\r\n" + "X-Padding: a\r\n".repeat(101) + "Content-Length: 2\r\n\r\n{}";
		try (RawPrometheus prometheus = RawPrometheus.writing(head)) {
			assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> query(prometheus.url(), MAX_BYTES))
				.withMessageStartingWith("cannot query " + prometheus.url() + ": not a valid HTTP response (");
		}
	}

	@Test
	void queryRange_portNobodyListensOn_isRefused() throws IOException {
		String url = "http://127.0.0.1:" + freePort();

		assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> query(url, MAX_BYTES))
			.withMessage("cannot query " + url + ": connection refused");
	}

	@Test
	void queryRange_unknownHost_saysSo() {
		HostResolver nothing = (host) -> {
			throw new UnknownHostException(host);
		};

		assertThatExceptionOfType(PrometheusException.class).isThrownBy(
				() -> query(new PrometheusServer("http://prom.invalid:9090", 5, MAX_BYTES, List.of(), false), nothing))
			.withMessage("cannot query http://prom.invalid:9090: unknown host");
	}

	@Test
	void queryRange_largeBodyWithoutALength_isCappedWhileRead() {
		String body = matrix(200);
		try (StubPrometheus prometheus = StubPrometheus
			.handling((exchange) -> StubPrometheus.send(exchange, 200, body, false))) {
			assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> query(prometheus.url(), 1024))
				.withMessage("the response from " + prometheus.url()
						+ " is larger than 1,024 bytes: use a larger step or a shorter range");
		}
	}

	@Test
	void queryRange_largeBodyWithoutALengthUnderTheCap_isRead() {
		String body = matrix(200);
		try (StubPrometheus prometheus = StubPrometheus
			.handling((exchange) -> StubPrometheus.send(exchange, 200, body, false))) {
			assertThat(query(prometheus.url(), MAX_BYTES)).hasSize(1);
		}
	}

	@Test
	void queryRange_announcedLengthOverTheCap_isRefusedBeforeTheBody() throws IOException {
		String head = "HTTP/1.1 200 OK\r\nContent-Length: 5000\r\n\r\n";
		try (RawPrometheus prometheus = RawPrometheus.trickling(head + "x".repeat(5000), head.length(), PAUSE)) {
			long started = System.nanoTime();
			assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> query(prometheus.url(), 1024))
				.withMessageContaining("is larger than 1,024 bytes");
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
		}
	}

	@Test
	void queryRange_answerThatIsNotJson_isRefusedWithoutRepeatingIt() {
		try (StubPrometheus prometheus = StubPrometheus.answering(200, "<html>an internal page</html>")) {
			assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> query(prometheus.url(), MAX_BYTES))
				.withMessage(prometheus.url() + " did not answer like a Prometheus server (HTTP 200)");
		}
	}

	/**
	 * Python's {@code json} fails on nesting this deep with a {@code RecursionError},
	 * which its API answered with {@code 500}.
	 */
	@Test
	void queryRange_jsonNestedDeeperThanPythonReads_isNotAPrometheusAnswer() {
		try (StubPrometheus prometheus = StubPrometheus.answering(200, "[".repeat(100_000))) {
			assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> query(prometheus.url(), MAX_BYTES))
				.withMessage(prometheus.url() + " did not answer like a Prometheus server (HTTP 200)");
		}
	}

	@ParameterizedTest
	@ValueSource(ints = { 200, 400 })
	void queryRange_refusal_namesItsKindButNeverWhatTheServerSaid(int status) {
		String refusal = "{\"status\": \"error\", \"errorType\": \"bad_data\", \"error\": \"SECRET-ROW-913\"}";
		try (StubPrometheus prometheus = StubPrometheus.answering(status, refusal)) {
			assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> query(prometheus.url(), MAX_BYTES))
				.satisfies((ex) -> {
					assertThat(ex.getMessage()).isEqualTo("query failed (bad_data): up");
					// kept for the command line, which runs for the person who named the
					// server
					assertThat(ex.detail()).hasValue("SECRET-ROW-913");
				});
		}
	}

	@Test
	void queryRange_successUnderAnErrorStatus_isRefusedWithTheStatus() {
		try (StubPrometheus prometheus = StubPrometheus.answering(500, StubPrometheus.SUCCESS)) {
			assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> query(prometheus.url(), MAX_BYTES))
				.withMessage(prometheus.url() + " answered HTTP 500: up");
		}
	}

	@Test
	void queryRange_hostOffTheAllowlist_isRefusedBeforeConnecting() {
		try (StubPrometheus prometheus = StubPrometheus.answering(200, matrix(1))) {
			assertThatExceptionOfType(PrometheusException.class)
				.isThrownBy(() -> query(server(prometheus.url(), List.of("prom.internal:9090")), HostResolver.SYSTEM))
				.withMessage("127.0.0.1 is not an allowed Prometheus host (ARCHSIM_PROMETHEUS_ALLOWED_HOSTS)");
			assertThat(prometheus.requests()).isEmpty();
		}
	}

	@Test
	void queryRange_hostOnTheAllowlist_isCalled() {
		try (StubPrometheus prometheus = StubPrometheus.answering(200, matrix(1))) {
			assertThat(query(server(prometheus.url(), List.of("127.0.0.1:" + prometheus.port())), HostResolver.SYSTEM))
				.hasSize(1);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "ftp://prom:9090", "http://", "http://user@:9090" })
	void queryRange_urlWithoutAnHttpHost_isRefused(String url) {
		assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> query(url, MAX_BYTES))
			.withMessage("the Prometheus URL must be http(s)://host[:port]");
	}

	@Test
	void queryRange_urlWithAPortThatIsNotOne_isRefused() {
		assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> query("http://prom:SECRET", MAX_BYTES))
			.withMessage("the Prometheus URL has an invalid port");
	}

	@Test
	void queryRange_query_isSentAsThePythonEngineSendsIt() {
		try (StubPrometheus prometheus = StubPrometheus.answering(200, StubPrometheus.SUCCESS);
				HttpPrometheus client = new HttpPrometheus(server(prometheus.url() + "/prefix//", List.of()), tls(),
						HostResolver.SYSTEM)) {
			client.queryRange("sum(rate(x{a=\"b c\"}[5m])) * 2 ~ é", START, START + 60.5, 15);

			assertThat(prometheus.requests()).singleElement().satisfies((request) -> {
				assertThat(request.uri()).isEqualTo("/prefix/api/v1/query_range?query=sum%28rate%28x%7Ba%3D%22b+c%22%7D"
						+ "%5B5m%5D%29%29+%2A+2+~+%C3%A9&start=1700000000.0&end=1700000060.5&step=15.0");
				assertThat(request.accept()).isEqualTo("application/json");
				assertThat(request.acceptEncoding()).isEqualTo("identity");
			});
			assertThat(client.source()).isEqualTo("Prometheus " + prometheus.url() + "/prefix//");
		}
	}

	@Test
	void queryRange_eachQuery_isItsOwnConnection() {
		try (StubPrometheus prometheus = StubPrometheus.answering(200, StubPrometheus.SUCCESS);
				HttpPrometheus client = new HttpPrometheus(server(prometheus.url(), List.of()), tls(),
						HostResolver.SYSTEM)) {
			client.queryRange("up", START, START + 60, 15);
			client.queryRange("up", START, START + 60, 15);

			assertThat(prometheus.requests()).extracting(Request::clientPort).doesNotHaveDuplicates().hasSize(2);
		}
	}

	private static void assertEndsAtTheDeadline(String url) {
		try (HttpPrometheus client = new HttpPrometheus(new PrometheusServer(url, 1.0, MAX_BYTES, List.of(), false),
				tls(), HostResolver.SYSTEM)) {
			long started = System.nanoTime();
			assertThatExceptionOfType(PrometheusException.class)
				.isThrownBy(() -> client.queryRange("up", START, START + 60, 15))
				.withMessage(url + " did not answer within 1 s");
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
		}
	}

	private static JsonNode query(String url, long maxBytes) {
		return query(new PrometheusServer(url, 5, maxBytes, List.of(), false), HostResolver.SYSTEM);
	}

	private static JsonNode query(PrometheusServer server, HostResolver resolver) {
		try (HttpPrometheus client = new HttpPrometheus(server, tls(), resolver)) {
			return client.queryRange("up", START, START + 60, 15);
		}
	}

	private static PrometheusServer server(String url, List<String> allowedHosts) {
		return new PrometheusServer(url, 5, MAX_BYTES, allowedHosts, false);
	}

	/**
	 * A range query's success with one series of points, as Python's test helper writes
	 * it.
	 */
	private static String matrix(int points) {
		String values = String.join(", ", Collections.nCopies(points, "[" + START + ", \"1\"]"));
		return "{\"status\": \"success\", \"data\": {\"resultType\": \"matrix\", \"result\": [{\"metric\": {}, "
				+ "\"values\": [" + values + "]}]}}";
	}

	private static SSLContext tls() {
		try {
			return SSLContext.getDefault();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

}
