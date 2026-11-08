package io.archsim.engine.random;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The generator's behaviour beyond the recorded draws ({@link RandomCasesTests}).
 */
class PythonRandomTests {

	@Test
	void seeded_negativeSeed_drawsAsItsAbsoluteValue() {
		PythonRandom negative = PythonRandom.seeded(BigInteger.valueOf(-12345));
		PythonRandom positive = PythonRandom.seeded(BigInteger.valueOf(12345));

		double drawn = negative.nextDouble();

		assertThat(drawn).isEqualTo(positive.nextDouble());
	}

	@Test
	void nextBits_noBits_isRefused() {
		PythonRandom random = PythonRandom.seeded(BigInteger.ONE);

		assertThatIllegalArgumentException().isThrownBy(() -> random.nextBits(0))
			.withMessage("nextBits takes 1 to 32 bits; got 0");
	}

	@Test
	void nextBits_moreThanAWord_isRefused() {
		PythonRandom random = PythonRandom.seeded(BigInteger.ONE);

		assertThatIllegalArgumentException().isThrownBy(() -> random.nextBits(33))
			.withMessage("nextBits takes 1 to 32 bits; got 33");
	}

	@Test
	void below_emptyRange_isRefusedInPythonsWords() {
		PythonRandom random = PythonRandom.seeded(BigInteger.ONE);

		assertThatIllegalArgumentException().isThrownBy(() -> random.below(0))
			.withMessage("empty range for randrange()");
	}

	@Test
	void below_oneValue_isAlwaysZero() {
		PythonRandom random = PythonRandom.seeded(BigInteger.TEN);

		int drawn = random.below(1);

		assertThat(drawn).isZero();
	}

}
