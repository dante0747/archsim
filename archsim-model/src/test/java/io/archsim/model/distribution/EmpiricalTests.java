package io.archsim.model.distribution;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A quantile table's own checks hold for any number, NaN included: a record can be made
 * without the codec, and a comparison with NaN is false whichever way it asks.
 */
class EmpiricalTests {

	@Test
	void new_aProbabilityThatIsNotANumber_isRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> table(new Quantile(0, 1), new Quantile(Double.NaN, 2), new Quantile(1, 3)))
			.withMessage("empirical quantile probabilities must rise strictly");
	}

	@Test
	void new_aValueThatIsNotANumber_isRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> table(new Quantile(0, 1), new Quantile(0.5, Double.NaN), new Quantile(1, 3)))
			.withMessage("empirical quantile values must not fall");
	}

	@Test
	void new_aFirstValueThatIsNotANumber_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> table(new Quantile(0, Double.NaN), new Quantile(1, 3)))
			.withMessage("empirical quantile values must not fall");
	}

	@Test
	void new_aLastValueThatIsNotANumber_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> table(new Quantile(0, 1), new Quantile(1, Double.NaN)))
			.withMessage("empirical quantile values must not fall");
	}

	private static Empirical table(Quantile... quantiles) {
		return new Empirical(List.of(quantiles));
	}

}
