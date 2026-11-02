package io.archsim.apitests;

import static io.archsim.apitests.Json.items;
import static io.archsim.apitests.Json.object;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /calibrate}: Prometheus from recorded responses (no live server) and
 * OpenTelemetry, the failures it explains, and what it never reads or repeats. From
 * {@code
 * services/api/tests/test_api_calibration.py}.
 */
@ApiTest(Profile.LOCAL)
class CalibrationTest {

	private static final JsonNode RECORDED = Json.read(Examples.engineFixture("calibration/starter-prometheus.json"));

	private static final String MAPPING = Examples.text(Examples.engineFixture("calibration/starter-mapping.yaml"));

	@Test
	void calibrateFromRecordedPrometheusAndOtlp(ApiClient client) {
		ApiResponse res = client.post("/calibrate", object("document", Examples.starter(), "source", "prometheus",
				"mapping", MAPPING, "recorded", RECORDED, "range", "2026-10-20T00:00:00Z/2026-10-21T00:00:00Z")); // not
																													// the
																													// recording's
																													// window
		assertThat(res.status()).as(res.text()).isEqualTo(200);
		JsonNode out = res.json();
		JsonNode serviceTime = out.path("document").path("provenance").path("nodes/api/serviceTimeMs");
		assertThat(serviceTime.path("timeRange").asString()).startsWith("2026-10-01T00:00:00Z");
		assertThat(items(out.path("notes"))).anyMatch(n -> n.asString().contains("the recording covers"));
		assertThat(out.path("changes").size()).isEqualTo(13);
		assertThat(out.path("document").path("schemaVersion").asString()).isEqualTo("1.7");
		assertThat(serviceTime.path("kind").asString()).isEqualTo("measured");
		assertThat(out.path("summary").asString()).startsWith("This model reproduces the observed p95");
		// the calibrated document is valid input for the rest of the API
		assertThat(client.post("/validate", object("document", out.path("document"))).json().path("valid").asBoolean())
			.isTrue();

		JsonNode traces = Json.read(Examples.engineFixture("calibration/checkout-traces.otlp.json"));
		res = client.post("/calibrate", object("document", Examples.starter(), "source", "otlp", "traces", traces,
				"mapping", "services: {checkout-api: api}\ndatabases: {orders: db}\n"));
		assertThat(res.status()).as(res.text()).isEqualTo(200);
		assertThat(Json.strings(res.json().path("changes"), "path")).contains("nodes/api/serviceTimeMs");
	}

	@Test
	void calibrateSaysWhatIsMissing(ApiClient client) {
		ApiResponse res = client.post("/calibrate",
				object("document", Examples.starter(), "source", "prometheus", "mapping", "nodes: {}"));
		assertThat(res.status()).isEqualTo(422);
		assertThat(res.message()).contains("URL or recorded");
		res = client.post("/calibrate", object("document", Examples.starter(), "source", "prometheus", "mapping",
				"nodes: {}", "prometheusUrl", "file:///etc/passwd"));
		assertThat(res.status()).isEqualTo(422);
		assertThat(res.message()).contains("http");
	}

	/**
	 * A mapping that names a file is YAML text, not a path to open. The file is made on
	 * this machine, so the check bites when the server runs here too (as started servers
	 * do).
	 */
	@Test
	void calibrateReadsNoServerFileAndRepeatsNoInput(ApiClient client, @TempDir Path tmp) throws IOException {
		Path secret = tmp.resolve("secret.yaml");
		Files.writeString(secret, "nodes: TOPSECRET-file-content-41\n", StandardCharsets.UTF_8);

		ApiResponse res = calibrate(client, object("mapping", secret.toString(), "recorded", RECORDED));
		assertThat(res.status()).isEqualTo(422);
		assertThat(res.text()).doesNotContain("TOPSECRET");
		assertThat(res.message()).contains("must be a YAML or JSON object");
		// validation errors name the place, never the value sent
		res = calibrate(client, object("mapping", "nodes: {api: {serviceTimeMs: {histogram: q, unit: SECRET-UNIT}}}"));
		assertThat(res.status()).isEqualTo(422);
		assertThat(res.text()).doesNotContain("SECRET-UNIT").doesNotContain("input_value");
		assertThat(res.text()).contains("nodes.api.serviceTimeMs");
		res = calibrate(client, object("mapping", "nodes: {}", "recorded",
				object("range", object("start", "a", "end", 5, "step", -7))));
		assertThat(res.status()).isEqualTo(422);
		assertThat(res.text()).doesNotContain("input_value").doesNotContain("-7");
		// broken YAML is a 422 too (it was a 500), without an excerpt of the text
		for (String[] sourceAndMapping : new String[][] { { "prometheus", "nodes: [SECRET-YAML" },
				{ "otlp", "a: [SECRET-YAML" } }) {
			res = client.post("/calibrate", object("document", Examples.starter(), "source", sourceAndMapping[0],
					"mapping", sourceAndMapping[1], "traces", object()));
			assertThat(res.status()).as(res.text()).isEqualTo(422);
			assertThat(res.text()).doesNotContain("SECRET-YAML");
		}
	}

	private static ApiResponse calibrate(ApiClient client, ObjectNode body) {
		ObjectNode all = object("document", Examples.starter(), "source", "prometheus");
		all.setAll(body);
		return client.post("/calibrate", all);
	}

	/** A Prometheus that cuts its answer off: a 422 that says so (it was a 500). */
	@Test
	void calibratePrometheusFailures(ApiClient client) throws IOException {
		try (CutOffServer prometheus = new CutOffServer()) {
			ApiResponse res = client.post("/calibrate", object("document", Examples.starter(), "source", "prometheus",
					"mapping", MAPPING, "replay", false, "prometheusUrl", prometheus.url()));
			assertThat(res.status()).as(res.text()).isEqualTo(422);
			assertThat(res.message()).contains("cut off");
		}
	}

	/**
	 * The second half of
	 * {@code test_calibrate_prometheus_failures_and_the_host_allowlist}.
	 */
	@Test
	void calibrateCallsOnlyAllowlistedHosts(@On(Profile.SMALL_RUNS) ApiClient client) throws IOException {
		try (CutOffServer prometheus = new CutOffServer()) {
			ApiResponse res = client.post("/calibrate", object("document", Examples.starter(), "source", "prometheus",
					"mapping", MAPPING, "replay", false, "prometheusUrl", prometheus.url()));
			assertThat(res.status()).isEqualTo(422);
			assertThat(res.message()).contains("not allowed");
		}
	}

	/**
	 * The replay run inside {@code /calibrate} keeps the server's limits (1,000 requests
	 * here).
	 */
	@Test
	void aReplayPastTheLimitsIsA422(@On(Profile.SMALL_RUNS) ApiClient client) {
		ApiResponse res = client.post("/calibrate", object("document", Examples.starter(), "source", "prometheus",
				"mapping", MAPPING, "recorded", RECORDED));
		assertThat(res.status()).as(res.text()).isEqualTo(422);
		assertThat(res.message()).contains("replay run hit a limit");
	}

	/**
	 * Answers every connection with a 200 that promises 1,000 bytes and sends a few, then
	 * closes.
	 */
	private static final class CutOffServer implements AutoCloseable {

		private static final byte[] CUT_OFF = "HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n{\"status\": "
			.getBytes(StandardCharsets.US_ASCII);

		private final ServerSocket socket;

		private final Thread thread;

		CutOffServer() throws IOException {
			socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			thread = Thread.ofVirtual().start(this::serve);
		}

		String url() {
			return "http://127.0.0.1:" + socket.getLocalPort();
		}

		private void serve() {
			while (!socket.isClosed()) {
				try (Socket connection = socket.accept()) {
					connection.getInputStream().read(new byte[8192]); // the request line
																		// and headers
					OutputStream out = connection.getOutputStream();
					out.write(CUT_OFF);
					out.flush();
				}
				catch (IOException e) {
					if (!socket.isClosed()) {
						throw new UncheckedIOException(e);
					}
				}
			}
		}

		@Override
		public void close() throws IOException {
			socket.close();
			thread.interrupt();
		}

	}

}
