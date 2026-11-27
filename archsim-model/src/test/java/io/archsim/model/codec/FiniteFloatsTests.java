package io.archsim.model.codec;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.SampleRecords.Loose;
import io.archsim.model.codec.SampleRecords.Point;
import io.archsim.model.codec.SampleRecords.Sample;
import io.archsim.model.codec.SampleRecords.Window;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A float is read only when it is finite. NaN and the infinities are refused however the
 * input writes them: as a word, as a number too large for a double, or as a float no JSON
 * text holds. Pydantic's lax mode read them; a design has no use for one.
 */
class FiniteFloatsTests {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final BindingError START_NOT_FINITE = CodecTestSupport.error("finite_number", "start",
			"Input should be a finite number");

	@ParameterizedTest
	@ValueSource(strings = { "\"nan\"", "\"NaN\"", "\" nan \"", "\"-nan\"", "\"+nan\"", "\"inf\"", "\"-inf\"",
			"\"+Inf\"", "\"Infinity\"", "\"-infinity\"", "\"iNfInItY\"" })
	void read_floatWrittenAsAWord_isRefused(String start) {
		List<BindingError> errors = CodecTestSupport.errors("{\"start\": " + start + ", \"end\": 1}", Window.class);

		assertThat(errors).containsExactly(START_NOT_FINITE);
	}

	@ParameterizedTest
	@ValueSource(strings = { "1e999", "-1e999", "\"1e999\"", "\"-1e999\"", "\"1_0e999\"" })
	void read_floatTooLargeForADouble_isRefused(String start) {
		List<BindingError> errors = CodecTestSupport.errors("{\"start\": " + start + ", \"end\": 1}", Window.class);

		assertThat(errors).containsExactly(START_NOT_FINITE);
	}

	@Test
	void read_wholeNumberTooLargeForADouble_isNotAValidNumber() {
		List<BindingError> errors = CodecTestSupport.errors("{\"start\": " + "1".repeat(400) + ", \"end\": 1}",
				Window.class);

		assertThat(errors)
			.containsExactly(CodecTestSupport.error("float_type", "start", "Input should be a valid number"));
	}

	@ParameterizedTest
	@ValueSource(doubles = { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY })
	void read_floatNodeThatIsNotFinite_isRefused(double start) {
		ObjectNode input = NODES.objectNode().put("start", start).put("end", 1.0);

		List<BindingError> errors = CodecTestSupport.errors(input, Window.class);

		assertThat(errors).containsExactly(START_NOT_FINITE);
	}

	@Test
	void read_infinityWhereOnlyALowerBoundHolds_isRefused() {
		List<BindingError> errors = CodecTestSupport.errors("{\"share\": \"inf\"}", Sample.class);

		assertThat(errors)
			.containsExactly(CodecTestSupport.error("finite_number", "share", "Input should be a finite number"));
	}

	@Test
	void read_nanInABoundedField_isRefusedAsNotFiniteRatherThanOutOfBounds() {
		List<BindingError> errors = CodecTestSupport.errors("{\"rate\": \"nan\"}", Sample.class);

		assertThat(errors)
			.containsExactly(CodecTestSupport.error("finite_number", "rate", "Input should be a finite number"));
	}

	@Test
	void read_floatThatIsNotFiniteInAList_isRefusedWhereItIs() {
		List<BindingError> errors = CodecTestSupport
			.errors("{\"anything\": null, \"settings\": {}, \"ratios\": [0.5, \"inf\"]}", Loose.class);

		assertThat(errors)
			.containsExactly(CodecTestSupport.error("finite_number", "ratios.1", "Input should be a finite number"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "1.7976931348623157e308", "-1.7976931348623157e308", "5e-324", "\"1e308\"", "1e-999" })
	void read_extremeFloatThatIsFinite_isRead(String time) {
		Point read = RecordCodec.read(CodecTestSupport.json("[" + time + ", 0]"), Point.class);

		assertThat(read.t()).isFinite();
	}

}
