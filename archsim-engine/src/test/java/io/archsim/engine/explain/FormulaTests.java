package io.archsim.engine.explain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A formula's result: rounded to four decimals as the report writes it, none when not
 * finite.
 */
class FormulaTests {

	private static final Formula FORMULA = Formula.of("x", "X", "x = y", "1 = 1");

	@Test
	void withResult_aNumber_isRoundedToFourDecimals() {
		assertThat(FORMULA.withResult(1.23456789, "ms").result()).isEqualTo(1.2346);
	}

	@Test
	void withResult_aNumberThatIsNotFinite_isNone() {
		assertThat(FORMULA.withResult(Double.POSITIVE_INFINITY, "req/s").result()).isNull();
	}

	@Test
	void of_aFormula_hasNoResultUnitNoteSourceOrMetric() {
		assertThat(FORMULA).isEqualTo(new Formula("x", "X", "x = y", "1 = 1", null, "", null, null, null));
	}

}
