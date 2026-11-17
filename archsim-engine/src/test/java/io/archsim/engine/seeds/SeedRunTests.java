package io.archsim.engine.seeds;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.slo.RunFigures;

import static org.assertj.core.api.Assertions.assertThat;

/** A seed group's run as its stored summary says: finished once it has a summary. */
class SeedRunTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void read_aCompactSummary_isFinishedWithItsFigures() {
		SeedRun run = SeedRun.read(42, JSON.readTree("{\"p50\": 40.0, \"p95\": 90.0, \"successRate\": 1.0}"));

		assertThat(run).isEqualTo(new SeedRun(42, new RunFigures(40.0, 90.0, null, 1.0, null, 1.0, null, false)));
	}

	@Test
	void read_noSummary_isNotFinished() {
		assertThat(SeedRun.read(42, null).figures()).isNull();
	}

	@Test
	void read_anEmptySummary_isNotFinished() {
		assertThat(SeedRun.read(42, JSON.readTree("{}")).figures()).isNull();
	}

}
