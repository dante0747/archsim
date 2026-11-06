package io.archsim.engine.check;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A check's settings keep their own list of what fails the check.
 */
class CheckSettingsTests {

	@Test
	void failOn_changedAfterwards_staysAsItWas() {
		List<FailOn> failOn = new ArrayList<>(List.of(FailOn.OBJECTIVES));
		CheckSettings settings = new CheckSettings(Thresholds.DEFAULTS, failOn);

		failOn.add(FailOn.REGRESSION);

		assertThat(settings.failOn()).containsExactly(FailOn.OBJECTIVES);
	}

}
