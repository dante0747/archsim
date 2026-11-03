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
	void of_removedPythonApi_failsListingTheChoices() {
		assertThatIllegalArgumentException().isThrownBy(() -> StartableApi.of("python"))
			.withMessage("archsim.api.start=python: the suite can start [java]");
	}

}
