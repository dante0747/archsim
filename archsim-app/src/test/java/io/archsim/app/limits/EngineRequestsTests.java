package io.archsim.app.limits;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.app.support.HostedTestSupport;
import io.archsim.app.support.UsedUpLimits;
import io.archsim.engine.analysis.DesignAnalysis;
import io.archsim.engine.analysis.DesignAnalyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;

/**
 * The engine's routes, on a server with one slot for them: a request holds the slot from
 * before its body is read until it is answered, so another waits for it, briefly, and is
 * then told to come back; and each person's requests to them are counted (ADR-0108).
 */
@TestPropertySource(properties = "archsim.max-engine-requests=1")
class EngineRequestsTests extends HostedTestSupport {

	private static final String VALIDATE = "/api/v1/validate";

	private static final String ANALYZE = "/api/v1/analyze";

	/** The analysis, held while a test needs the slot taken. */
	@MockitoBean
	private DesignAnalyzer analyzer;

	@Test
	void validate_whileAnotherDesignHoldsTheOnlySlot_isBusyWithRetryAfter() throws Exception {
		SignedIn person = signedIn("Ada");
		CountDownLatch analyzing = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(1);
		DesignAnalysis analysis = analysis();
		willAnswer((call) -> {
			analyzing.countDown();
			done.await();
			return analysis;
		}).given(this.analyzer).analyze(any(), any());

		try (ExecutorService other = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<MvcTestResult> holding = other.submit(() -> send(person, HttpMethod.POST, ANALYZE, design()));
			MvcTestResult busy;
			try {
				assertThat(analyzing.await(10, TimeUnit.SECONDS)).isTrue();
				busy = send(person, HttpMethod.POST, VALIDATE, design());
			}
			finally {
				done.countDown();
			}

			assertThat(busy).hasStatus(503).hasHeader("Retry-After", "1");
			assertThat(json(busy).path("detail").path("message").asString())
				.isEqualTo("The server is busy with other designs: try again in a moment");
			assertThat(holding.get()).hasStatus(200);
		}
	}

	@Test
	void validate_onceTheDesignHoldingTheSlotIsAnswered_isServed() throws IOException {
		SignedIn person = signedIn("Ada");
		DesignAnalysis analysis = analysis();
		given(this.analyzer.analyze(any(), any())).willReturn(analysis);
		assertThat(send(person, HttpMethod.POST, ANALYZE, design())).hasStatus(200);

		assertThat(send(person, HttpMethod.POST, VALIDATE, design())).hasStatus(200);
	}

	@Test
	void validate_afterARequestRefusedForItsBody_isServed() throws IOException {
		SignedIn person = signedIn("Ada");
		assertThat(send(person, HttpMethod.POST, VALIDATE, "{not json")).hasStatus(422);

		assertThat(send(person, HttpMethod.POST, VALIDATE, design())).hasStatus(200);
	}

	@Test
	void analyze_afterThePersonsEngineRequestsThisMinute_isTooManyWithRetryAfter() throws IOException {
		SignedIn person = signedIn("Ada");
		UsedUpLimits.useUp(jdbc(), "user:" + person.userId() + ":engine", RateLimit.ENGINE_REQUESTS_PER_USER);

		MvcTestResult refused = send(person, HttpMethod.POST, ANALYZE, design());

		assertThat(refused).hasStatus(429);
		assertThat(refused.getResponse().getHeader("Retry-After")).matches("\\d+");
		assertThat(json(refused).path("detail").path("message").asString())
			.startsWith("You have sent many designs to the engine this minute.");
	}

	private static DesignAnalysis analysis() {
		DesignAnalysis analysis = mock();
		given(analysis.toJson()).willReturn(JsonNodeFactory.instance.objectNode());
		return analysis;
	}

	private static String design() throws IOException {
		Path root = Path.of(System.getProperty("archsim.repoRoot", ".."));
		return "{\"document\": " + Files.readString(root.resolve("examples/cache-aside-starter.json")) + "}";
	}

}
