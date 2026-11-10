package io.archsim.cli;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.engine.calibration.prometheus.http.HttpPrometheus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The server {@code archsim calibrate prometheus --url} calls, on the open policy the
 * Python CLI called it on (ADR-0057): with no allowlist, at any address, loopback and
 * private ones included, which the app's server-side calls refuse; and its logs, which go
 * nowhere, so that only the command's own lines reach standard error.
 */
class PrometheusClientsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String QUERY = "up";

	private static final double START = 1_700_000_000.0;

	private static final double STEP = 60.0;

	/** Where SLF4J finds its providers, as a service. */
	private static final String SLF4J_PROVIDERS = "META-INF/services/org.slf4j.spi.SLF4JServiceProvider";

	@ParameterizedTest
	@ValueSource(strings = { "127.0.0.1", "localhost" })
	void server_aLoopbackHostNoAllowlistNames_isCalled(String host) throws IOException {
		try (PrometheusStub stub = PrometheusStub.behaving(answering());
				HttpPrometheus server = PrometheusClients.server(stub.url().replace("127.0.0.1", host))) {
			JsonNode series = server.queryRange(QUERY, START, START + STEP, STEP);

			assertThat(series).isEqualTo(JSON.readTree("[{\"metric\": {}, \"values\": [[1700000000, \"1\"]]}]"));
		}
	}

	@Test
	void slf4jProviders_onTheCommandsClassPath_areTheNoOperationOneAlone() throws IOException {
		// with no provider SLF4J warns on standard error when the client first
		// logs; with another, such as Logback, the client's lines are printed
		List<String> providers = new ArrayList<>();
		for (URL found : Collections.list(getClass().getClassLoader().getResources(SLF4J_PROVIDERS))) {
			try (InputStream in = found.openStream()) {
				providers.add(new String(in.readAllBytes(), StandardCharsets.UTF_8).strip());
			}
		}

		assertThat(providers).containsExactly("org.slf4j.nop.NOPServiceProvider");
		// SLF4J 1.7, which finds no provider so, would print its warning anyway
		assertThatCode(() -> Class.forName("org.slf4j.spi.SLF4JServiceProvider")).doesNotThrowAnyException();
	}

	// a case whose stub answers the query with one series
	private static JsonNode answering() {
		String responses = "{\"responses\": {\"" + QUERY + "\": {\"status\": \"success\", \"data\": "
				+ "{\"resultType\": \"matrix\", \"result\": [{\"metric\": {}, \"values\": [[1700000000, \"1\"]]}]}}}}";
		return JsonNodeFactory.instance.objectNode()
			.put("server", "recorded")
			.set("inputs", JsonNodeFactory.instance.objectNode().put("{recorded}", responses));
	}

}
