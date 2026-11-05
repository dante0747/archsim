package io.archsim.app.engine;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Which engine calls the client sends once more: those whose connection dropped before an
 * answer, not those that timed out, were refused or were interrupted. Each client here
 * fails every attempt as the HTTP client fails it, and counts them.
 */
class EngineServiceRetryTests {

	private static final JsonNode DESIGN = JsonMapper.builder()
		.build()
		.readTree("{\"kind\": \"archsim.architecture\"}");

	private final AtomicInteger attempts = new AtomicInteger();

	@AfterEach
	void clearInterrupt() {
		Thread.interrupted();
	}

	@Test
	void export_connectionDroppedBeforeAnAnswer_isSentOnceMore() {
		EngineService engine = failingWith(new IOException("HTTP/1.1 header parser received no bytes"));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> engine.export(DESIGN, "mermaid"))
			.withMessageStartingWith("The simulation engine is not available right now");
		assertThat(this.attempts).hasValue(2);
	}

	@Test
	void export_timedOut_isSentOnce() {
		EngineService engine = failingWith(new HttpTimeoutException("request timed out"));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> engine.export(DESIGN, "mermaid"))
			.withMessageStartingWith("The simulation engine took too long to answer");
		assertThat(this.attempts).hasValue(1);
	}

	@Test
	void export_connectionRefused_isSentOnce() {
		EngineService engine = failingWith(new ConnectException("Connection refused"));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> engine.export(DESIGN, "mermaid"))
			.withMessageStartingWith("The simulation engine is not available right now");
		assertThat(this.attempts).hasValue(1);
	}

	@Test
	void export_interrupted_isSentOnce() {
		EngineService engine = new EngineService(failingClient(() -> {
			Thread.currentThread().interrupt();
			return new IOException("Request was interrupted");
		}));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> engine.export(DESIGN, "mermaid"));
		assertThat(this.attempts).hasValue(1);
	}

	/**
	 * A client whose every attempt fails before an answer, as the HTTP client fails.
	 * @param failure how each attempt fails
	 * @return the client
	 */
	private EngineService failingWith(IOException failure) {
		return new EngineService(failingClient(() -> failure));
	}

	private RestClient failingClient(Supplier<IOException> failure) {
		return RestClient.builder().baseUrl("http://engine").requestFactory((uri, method) -> {
			this.attempts.incrementAndGet();
			throw failure.get();
		}).build();
	}

}
