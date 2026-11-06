package io.archsim.app.engine;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.app.config.CalibrationProperties.Outbound;
import io.archsim.app.limits.WorkspaceLimits.RunLimits;
import io.archsim.app.web.ApiException;
import io.archsim.app.web.JsonText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Which engine calls the client sends once more: those whose connection dropped before an
 * answer, not those that timed out, were refused or were interrupted. Each client here
 * fails every attempt as the HTTP client fails it, and counts them.
 */
class EngineServiceRetryTests {

	private static final OutboundPolicy POLICY = new OutboundPolicy(Outbound.OFF, List.of(), 1, 1);

	private static final RunLimits REPLAY = new RunLimits(1, 1);

	private final AtomicInteger attempts = new AtomicInteger();

	@AfterEach
	void clearInterrupt() {
		Thread.interrupted();
	}

	@Test
	void calibrate_connectionDroppedBeforeAnAnswer_isSentOnceMore() {
		EngineService engine = failingWith(new IOException("HTTP/1.1 header parser received no bytes"));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> calibrate(engine))
			.withMessageStartingWith("The simulation engine is not available right now");
		assertThat(this.attempts).hasValue(2);
	}

	@Test
	void calibrate_timedOut_isSentOnce() {
		EngineService engine = failingWith(new HttpTimeoutException("request timed out"));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> calibrate(engine))
			.withMessageStartingWith("The simulation engine took too long to answer");
		assertThat(this.attempts).hasValue(1);
	}

	@Test
	void calibrate_connectionRefused_isSentOnce() {
		EngineService engine = failingWith(new ConnectException("Connection refused"));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> calibrate(engine))
			.withMessageStartingWith("The simulation engine is not available right now");
		assertThat(this.attempts).hasValue(1);
	}

	@Test
	void calibrate_interrupted_isSentOnce() {
		EngineService engine = new EngineService(failingClient(() -> {
			Thread.currentThread().interrupt();
			return new IOException("Request was interrupted");
		}));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> calibrate(engine));
		assertThat(this.attempts).hasValue(1);
	}

	private static JsonText calibrate(EngineService engine) {
		return engine.calibrate(JsonNodeFactory.instance.objectNode(), POLICY, REPLAY);
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
