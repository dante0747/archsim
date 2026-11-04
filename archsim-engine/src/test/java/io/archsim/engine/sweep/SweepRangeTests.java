package io.archsim.engine.sweep;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.Workload;

import static org.assertj.core.api.Assertions.assertThat;

/** The loads a sweep steps through. */
class SweepRangeTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void suggested_aLoad_runsFromHalfToThreeTimesItOnRoundNumbers() {
		assertThat(SweepRange.suggested(400, SweepRange.MAX_RPS)).isEqualTo(new SweepRange(200, 1200));
	}

	@Test
	void suggested_noLoad_startsFromAHundred() {
		assertThat(SweepRange.suggested(0, SweepRange.MAX_RPS)).isEqualTo(new SweepRange(50, 300));
	}

	@Test
	void suggested_aCap_limitsBothEnds() {
		assertThat(SweepRange.suggested(40_000, 50_000)).isEqualTo(new SweepRange(20_000, 50_000));
	}

	@Test
	void around_aShortStep_capsTheRangeAsATwoSecondStepWould() {
		Workload workload = RecordCodec.read(JSON.readTree("{\"rps\": 60000}"), Workload.class);

		assertThat(SweepRange.around(workload, new SimulationLimits(100_000, 300), 1))
			.isEqualTo(new SweepRange(40_000, 50_000));
	}

	@Test
	void rpsCap_aLongStep_isLimitedByTheRequestsARunMayMake() {
		assertThat(SweepRange.rpsCap(SimulationLimits.DEFAULT, 60)).isEqualTo(2_000_000 / 60.0);
	}

	@Test
	void rpsCap_aShortStep_isTheSchemasHighestLoad() {
		assertThat(SweepRange.rpsCap(SimulationLimits.DEFAULT, 2)).isEqualTo(SweepRange.MAX_RPS);
	}

	@Test
	void levels_aRange_isEvenlySpacedAndRounded() {
		assertThat(new SweepRange(300, 50).levels(4)).containsExactly(50.0, 135.0, 215.0, 300.0);
	}

	@Test
	void levels_aRangeFromZero_startsAtATenth() {
		assertThat(new SweepRange(0, 10).levels(6)).containsExactly(0.1, 2.1, 4.1, 6.0, 8.0, 10.0);
	}

	@Test
	void levels_stepsThatRoundAlike_areListedOnce() {
		assertThat(new SweepRange(12, 18).levels(10)).containsExactly(12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0);
	}

	@Test
	void levels_fewerThanTwoSteps_takesTwo() {
		assertThat(new SweepRange(5, 200_000).levels(1)).containsExactly(5.0, 200_000.0);
	}

}
