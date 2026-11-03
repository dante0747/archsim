package io.archsim.apitests;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** The APIs {@code archsim.api.start} may name. */
class StartableApiTests {

	@Test
	void of_nameInAnyCase_isThatApi() {
		assertThat(StartableApi.of(" Java ")).isEqualTo(StartableApi.JAVA);
	}

	@Test
	void of_unknownName_failsListingTheChoices() {
		assertThatIllegalArgumentException().isThrownBy(() -> StartableApi.of("ruby"))
			.withMessage("archsim.api.start=ruby: the suite can start [python, java]");
	}

}
