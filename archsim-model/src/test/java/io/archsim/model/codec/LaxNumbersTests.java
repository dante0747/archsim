package io.archsim.model.codec;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Numbers read out of strings as pydantic's lax mode reads them, however many groups of
 * digits underscores split them into.
 */
class LaxNumbersTests {

	private static final String GROUPS = "0_".repeat(100_000);

	@Test
	void parseInteger_aHundredThousandGroups_isTheNumber() {
		assertThat(LaxNumbers.parseInteger(GROUPS + "5")).hasValue(BigInteger.valueOf(5));
	}

	@Test
	void parseFloat_aHundredThousandGroups_isTheNumber() {
		assertThat(LaxNumbers.parseFloat(GROUPS + "5.5")).hasValue(5.5);
	}

}
