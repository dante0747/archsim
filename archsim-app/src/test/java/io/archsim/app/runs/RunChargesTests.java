package io.archsim.app.runs;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import io.archsim.app.engine.EngineRefusalException;
import io.archsim.app.engine.PrepareRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.willThrow;

/**
 * Runs against the hourly limits: a run is counted only once the engine prepared it and
 * every check passed, so a refused run never uses up one of the hour's.
 */
@TestPropertySource(properties = "archsim.rate-limits=true")
class RunChargesTests extends RunTestSupport {

	@Test
	void create_overTheHour_isTooManyRequests() {
		prepares(42, null, true);
		String project = project("team", "editor");
		runsPerHour(project, 1);
		started(start(project, "{}"));

		assertThat(start(project, "{}")).hasStatus(429)
			.bodyJson()
			.extractingPath("$.detail.message")
			.asString()
			.startsWith("Lab has started its 1 runs for this hour.");
	}

	@Test
	void create_runTheEngineRefuses_isNotCountedAgainstTheHour() {
		String project = project("team", "editor");
		runsPerHour(project, 1);
		willThrow(EngineRefusalException.runOverLimits("Expected too many requests")).given(engine()).prepare(any());
		assertThat(start(project, "{}")).hasStatus(422);
		prepares(42, null, true);

		assertThat(start(project, "{}")).hasStatus(202);
	}

	@Test
	void rerun_runTheEngineRefuses_isNotCountedAgainstTheHour() {
		prepares(42, null, true);
		String project = project("team", "editor");
		runsPerHour(project, 2);
		String run = started(start(project, "{}")).path("id").asString();
		willThrow(EngineRefusalException.runOverLimits("Expected too many requests")).given(engine()).prepare(any());
		assertThat(post("/api/v1/runs/" + run + "/rerun", "")).hasStatus(422);
		prepares(42, null, true);

		assertThat(post("/api/v1/runs/" + run + "/rerun", "")).hasStatus(202);
	}

	@Test
	void create_seedGroupOverTheSeedCap_isNotCountedAgainstTheHour() {
		prepares(42, null, true);
		String project = project("team", "editor");
		runsPerHour(project, 6);
		assertThat(start(project, "{\"seeds\": 6}")).hasStatus(422);

		assertThat(start(project, "{}")).hasStatus(202);
	}

	@Test
	void create_seedGroupWhoseThirdSeedTheEngineRefuses_isNotCountedAgainstTheHour() {
		prepares(42, null, true);
		willThrow(EngineRefusalException.runOverLimits("Expected too many requests")).given(engine())
			.prepare(argThat((PrepareRequest request) -> Long.valueOf(44).equals(request.seed())));
		String project = project("team", "editor");
		runsPerHour(project, 3);
		assertThat(start(project, "{\"seeds\": 3}")).hasStatus(422);
		prepares(42, null, true);

		assertThat(start(project, "{\"seeds\": 3}")).hasStatus(202);
	}

}
