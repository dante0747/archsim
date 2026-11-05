package io.archsim.engine.slo;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.document.Objectives;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The objectives stored with a run, read as the Python engine read them: a target the
 * design leaves out is off, not the model's default.
 */
class StoredObjectivesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Objectives NONE = new Objectives(null, null, null, null);

	@Test
	void read_aDesignThatSetsOnlyAP99_leavesTheOtherTargetsOff() {
		assertThat(StoredObjectives.read(JSON.readTree("{\"latencyP99Ms\": 500}")))
			.isEqualTo(new Objectives(null, 500.0, null, null));
	}

	@Test
	void read_targetsSetToNull_areOff() {
		assertThat(StoredObjectives.read(JSON.readTree("{\"latencyP95Ms\": null, \"availability\": 0.999}")))
			.isEqualTo(new Objectives(null, null, 0.999, null));
	}

	@Test
	void read_noObjectives_setNone() {
		assertThat(StoredObjectives.read(null)).isEqualTo(NONE);
	}

	@Test
	void read_objectivesThatAreNull_setNone() {
		assertThat(StoredObjectives.read(JSON.readTree("null"))).isEqualTo(NONE);
	}

}
