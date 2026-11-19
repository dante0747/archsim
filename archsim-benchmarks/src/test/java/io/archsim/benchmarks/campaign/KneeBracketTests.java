package io.archsim.benchmarks.campaign;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a run measures next to close in on its knee: between the steps either side of it,
 * or below a ladder that missed the objective from its lowest step.
 */
class KneeBracketTests {

	@Test
	void next_stepsEitherSideOfTheKneeFarApart_isHalfwayBetweenThem() {
		assertThat(KneeBracket.next(List.of(met(1000), missed(2000)))).contains(1500L);
	}

	// 60 req/s is 3% of 2,000
	@Test
	void next_stepsWithinThreePercentOfTheHigher_isNone() {
		assertThat(KneeBracket.next(List.of(met(1940), missed(2000)))).isEmpty();
	}

	@Test
	void next_stepsJustOverThreePercentApart_isHalfwayBetweenThem() {
		assertThat(KneeBracket.next(List.of(met(1938), missed(2000)))).contains(1969L);
	}

	// 150.5, rounded half to even as the ladder's steps are
	@Test
	void next_aLoadHalfwayWithAHalf_isRoundedToEven() {
		assertThat(KneeBracket.next(List.of(met(100), missed(201)))).contains(150L);
	}

	@Test
	void next_theLowestStepMissing_isHalfOfIt() {
		assertThat(KneeBracket.next(List.of(missed(1000), missed(2000)))).contains(500L);
	}

	// half of it would be 6 req/s, under the least load a run measures
	@Test
	void next_aLowestStepOfTwelveMissing_isNone() {
		assertThat(KneeBracket.next(List.of(missed(12)))).isEmpty();
	}

	// the stack is down, not slow: no lower load would tell more
	@Test
	void next_aLowestStepNoRequestSucceededAt_isNone() {
		JsonNode failed = JsonText.read("{\"offeredRps\": 1000, \"p95\": null, \"successRate\": 0.0}");

		assertThat(KneeBracket.next(List.of(failed))).isEmpty();
	}

	// the ladder climbs instead
	@Test
	void next_everyStepMeeting_isNone() {
		assertThat(KneeBracket.next(List.of(met(1000), met(2000)))).isEmpty();
	}

	// a stable stack does not get faster with more load: the run was disturbed
	@Test
	void next_aStepMeetingAboveOneThatMissed_isNone() {
		assertThat(KneeBracket.next(List.of(met(1000), missed(2000), met(3000)))).isEmpty();
	}

	@Test
	void next_stepsInTheOrderTheyWereMeasured_areReadByLoad() {
		assertThat(KneeBracket.next(List.of(met(1000), missed(2000), met(1500)))).contains(1750L);
	}

	// no whole load lies between 10 and 11 req/s
	@Test
	void next_neighbouringLoads_isNone() {
		assertThat(KneeBracket.next(List.of(met(10), missed(11)))).isEmpty();
	}

	private static JsonNode met(long rps) {
		return JsonText.read("{\"offeredRps\": " + rps + ", \"p95\": 10.0, \"successRate\": 1.0}");
	}

	private static JsonNode missed(long rps) {
		return JsonText.read("{\"offeredRps\": " + rps + ", \"p95\": 80.0, \"successRate\": 1.0}");
	}

}
