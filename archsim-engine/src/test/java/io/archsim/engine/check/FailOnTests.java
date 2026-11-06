package io.archsim.engine.check;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * What fails a check, by the name {@code --fail-on} gives it.
 */
class FailOnTests {

	@Test
	void of_eachName_isItsChoice() {
		assertThat(FailOn.of("objectives")).isEqualTo(FailOn.OBJECTIVES);
		assertThat(FailOn.of("regression")).isEqualTo(FailOn.REGRESSION);
	}

	@Test
	void of_anotherName_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> FailOn.of("cost"))
			.withMessage("A check fails on objectives or regression, not cost");
	}

}
