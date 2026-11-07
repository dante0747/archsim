package io.archsim.engine.calibration.mapping;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.archsim.model.codec.BindingError;
import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.Location;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Errors worded as the Python engine's {@code validation_message} words them.
 */
class ValidationMessagesTests {

	@Test
	void of_errorAtTheTopLevel_saysSo() {
		BindingException ex = new BindingException(
				List.of(new BindingError("value_error", Location.ROOT, "Value error, x")));

		assertThat(ValidationMessages.of(ex)).isEqualTo("(top level): Value error, x");
	}

	@Test
	void of_moreThanTenErrors_listsTenAndCountsTheRest() {
		List<BindingError> errors = IntStream.range(0, 12)
			.mapToObj((index) -> new BindingError("missing", Location.ROOT.child("f" + index), "Field required"))
			.toList();

		assertThat(ValidationMessages.of(new BindingException(errors))).startsWith("f0: Field required; f1: ")
			.endsWith("; f9: Field required; ... and 2 more");
	}

}
