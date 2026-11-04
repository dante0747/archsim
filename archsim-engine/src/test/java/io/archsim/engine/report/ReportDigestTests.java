package io.archsim.engine.report;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** The digest that names a report's content: Python's {@code canonical_digest} values. */
class ReportDigestTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void of_anEmptyObject_isTheSha256OfItsCanonicalText() {
		assertThat(ReportDigest.of(JSON.readTree("{}")))
			.isEqualTo("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
	}

	@Test
	void of_keysInAnotherOrder_isTheSame() {
		String digest = ReportDigest.of(JSON.readTree("{\"b\": 1, \"a\": [1, 2.5, \"x\"]}"));

		assertThat(digest).isEqualTo(ReportDigest.of(JSON.readTree("{\"a\": [1, 2.5, \"x\"], \"b\": 1}")))
			.isEqualTo("169e17f8f54ae3608a7cad7defdcde468158fd398e9b0adf9c3628409347842d");
	}

	@Test
	void of_nonAsciiText_hashesItEscaped() {
		assertThat(ReportDigest.of(JSON.readTree("{\"u\": \"café ✓ 😀\"}")))
			.isEqualTo("0dd32864577569338c37aa6f9b36e6156b47006cdefe82cc57da2446c1253f03");
	}

	@Test
	void of_aNumberThatIsNotFinite_isRefused() {
		ObjectNode value = JsonNodeFactory.instance.objectNode();
		value.putArray("x").add(Double.NaN);

		assertThatIllegalArgumentException().isThrownBy(() -> ReportDigest.of(value))
			.withMessage("Out of range float values are not JSON compliant: nan");
	}

}
