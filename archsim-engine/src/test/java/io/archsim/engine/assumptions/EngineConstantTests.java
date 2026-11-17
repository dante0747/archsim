package io.archsim.engine.assumptions;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** A measured constant names the results that measured it (ADR-0085). */
class EngineConstantTests {

	@Test
	void create_measuredWithNoResults_isRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(
					() -> new EngineConstant("Auto-sizing", "Kafka fixed cores", "0.39", "sizing.KAFKA", null, true))
			.withMessageContaining("Kafka fixed cores");
	}

}
