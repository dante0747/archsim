package io.archsim.apitests;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /calibrate}: Prometheus from recorded responses (no live server) and
 * OpenTelemetry, the failures it explains, and what it never reads or repeats. From
 * {@code
 * services/api/tests/test_api_calibration.py}.
 */
@ApiTest(Profile.LOCAL)
class CalibrationTests {

	private static final JsonNode RECORDED = Json.read(Examples.fixture("calibration/starter-prometheus.json"));

	private static final String MAPPING = Examples.text(Examples.fixture("calibration/starter-mapping.yaml"));

	@Test
	void calibrate_withRecordedPrometheusOrOtlp_returnsCalibratedDocument(ApiClient client) {
		// the range is not the recording's window
		ApiResponse response = client.post("/calibrate",
				Json.object("document", Examples.starter(), "source", "prometheus", "mapping", MAPPING, "recorded",
						RECORDED, "range", "2026-10-20T00:00:00Z/2026-10-21T00:00:00Z"));
		assertThat(response.status()).as(response.text()).isEqualTo(200);
		JsonNode out = response.json();
		JsonNode serviceTime = out.path("document").path("provenance").path("nodes/api/serviceTimeMs");
		assertThat(serviceTime.path("timeRange").asString()).startsWith("2026-10-01T00:00:00Z");
		assertThat(Json.items(out.path("notes"))).anyMatch((note) -> note.asString().contains("the recording covers"));
		assertThat(out.path("changes").size()).isEqualTo(13);
		assertThat(out.path("document").path("schemaVersion").asString()).isEqualTo("1.9");
		assertThat(serviceTime.path("kind").asString()).isEqualTo("measured");
		assertThat(out.path("summary").asString()).startsWith("This model reproduces the observed p95");
		// the calibrated document is valid input for the rest of the API
		assertThat(client.post("/validate", Json.object("document", out.path("document")))
			.json()
			.path("valid")
			.asBoolean()).isTrue();

		JsonNode traces = Json.read(Examples.fixture("calibration/checkout-traces.otlp.json"));
		response = client.post("/calibrate", Json.object("document", Examples.starter(), "source", "otlp", "traces",
				traces, "mapping", "services: {checkout-api: api}\ndatabases: {orders: db}\n"));
		assertThat(response.status()).as(response.text()).isEqualTo(200);
		assertThat(Json.strings(response.json().path("changes"), "path")).contains("nodes/api/serviceTimeMs");
	}

	@Test
	void calibrate_withoutUsableSource_saysWhatIsMissing(ApiClient client) {
		ApiResponse response = client.post("/calibrate",
				Json.object("document", Examples.starter(), "source", "prometheus", "mapping", "nodes: {}"));
		assertThat(response.status()).isEqualTo(422);
		assertThat(response.message()).contains("URL or recorded");
		response = client.post("/calibrate", Json.object("document", Examples.starter(), "source", "prometheus",
				"mapping", "nodes: {}", "prometheusUrl", "file:///etc/passwd"));
		assertThat(response.status()).isEqualTo(422);
		assertThat(response.message()).contains("http");
	}

	/**
	 * A mapping that names a file is YAML text, not a path to open. The file is made on
	 * this machine, so the check bites when the server runs here too (as started servers
	 * do).
	 */
	@Test
	void calibrate_withSecretInput_readsNoFileAndRepeatsNoInput(ApiClient client, @TempDir Path directory)
			throws IOException {
		Path secret = directory.resolve("secret.yaml");
		Files.writeString(secret, "nodes: TOPSECRET-file-content-41\n", StandardCharsets.UTF_8);

		ApiResponse response = calibrate(client, Json.object("mapping", secret.toString(), "recorded", RECORDED));
		assertThat(response.status()).isEqualTo(422);
		assertThat(response.text()).doesNotContain("TOPSECRET");
		assertThat(response.message()).contains("must be a YAML or JSON object");
		// validation errors name the place, never the value sent
		response = calibrate(client,
				Json.object("mapping", "nodes: {api: {serviceTimeMs: {histogram: q, unit: SECRET-UNIT}}}"));
		assertThat(response.status()).isEqualTo(422);
		assertThat(response.text()).doesNotContain("SECRET-UNIT").doesNotContain("input_value");
		assertThat(response.text()).contains("nodes.api.serviceTimeMs");
		response = calibrate(client, Json.object("mapping", "nodes: {}", "recorded",
				Json.object("range", Json.object("start", "a", "end", 5, "step", -7))));
		assertThat(response.status()).isEqualTo(422);
		assertThat(response.text()).doesNotContain("input_value").doesNotContain("-7");
		// broken YAML is a 422 too (it was a 500), without an excerpt of the text
		for (String[] sourceAndMapping : new String[][] { { "prometheus", "nodes: [SECRET-YAML" },
				{ "otlp", "a: [SECRET-YAML" } }) {
			response = client.post("/calibrate", Json.object("document", Examples.starter(), "source",
					sourceAndMapping[0], "mapping", sourceAndMapping[1], "traces", Json.object()));
			assertThat(response.status()).as(response.text()).isEqualTo(422);
			assertThat(response.text()).doesNotContain("SECRET-YAML");
		}
	}

	private static ApiResponse calibrate(ApiClient client, ObjectNode body) {
		ObjectNode all = Json.object("document", Examples.starter(), "source", "prometheus");
		all.setAll(body);
		return client.post("/calibrate", all);
	}

	/** A Prometheus that cuts its answer off: a 422 that says so (it was a 500). */
	@Test
	void calibrate_withCutOffPrometheus_explainsTheFailure(ApiClient client) throws IOException {
		try (CutOffServer prometheus = new CutOffServer()) {
			ApiResponse response = client.post("/calibrate", Json.object("document", Examples.starter(), "source",
					"prometheus", "mapping", MAPPING, "replay", false, "prometheusUrl", prometheus.url()));
			assertThat(response.status()).as(response.text()).isEqualTo(422);
			assertThat(response.message()).contains("cut off");
		}
	}

	/**
	 * The second half of
	 * {@code test_calibrate_prometheus_failures_and_the_host_allowlist}.
	 */
	@Test
	void calibrate_withHostOffTheAllowlist_isRefused(@On(Profile.SMALL_RUNS) ApiClient client) throws IOException {
		try (CutOffServer prometheus = new CutOffServer()) {
			ApiResponse response = client.post("/calibrate", Json.object("document", Examples.starter(), "source",
					"prometheus", "mapping", MAPPING, "replay", false, "prometheusUrl", prometheus.url()));
			assertThat(response.status()).isEqualTo(422);
			assertThat(response.message()).contains("not allowed");
		}
	}

	/**
	 * The replay run inside {@code /calibrate} keeps the server's limits (1,000 requests
	 * here).
	 */
	@Test
	void calibrate_withReplayPastTheLimits_is422(@On(Profile.SMALL_RUNS) ApiClient client) {
		ApiResponse response = client.post("/calibrate", Json.object("document", Examples.starter(), "source",
				"prometheus", "mapping", MAPPING, "recorded", RECORDED));
		assertThat(response.status()).as(response.text()).isEqualTo(422);
		assertThat(response.message()).contains("replay run hit a limit");
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
			this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			this.thread = Thread.ofVirtual().start(this::serve);
		}

		String url() {
			return "http://127.0.0.1:" + this.socket.getLocalPort();
		}

		private void serve() {
			while (!this.socket.isClosed()) {
				try (Socket connection = this.socket.accept()) {
					// the request line and headers
					connection.getInputStream().read(new byte[8192]);
					OutputStream out = connection.getOutputStream();
					out.write(CUT_OFF);
					out.flush();
				}
				catch (IOException ex) {
					if (!this.socket.isClosed()) {
						throw new UncheckedIOException(ex);
					}
				}
			}
		}

		@Override
		public void close() throws IOException {
			this.socket.close();
			this.thread.interrupt();
		}

	}

}
