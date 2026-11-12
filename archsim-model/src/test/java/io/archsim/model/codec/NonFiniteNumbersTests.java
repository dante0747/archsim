package io.archsim.model.codec;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.SampleRecords.Loose;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writing a record gives pydantic's JSON mode, which writes a float that is not finite as
 * {@code null} where an {@code Any} value holds it, however deep, and keeps it in a float
 * field.
 */
class NonFiniteNumbersTests {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	@Test
	void write_nonFiniteNumbersInAnAnyObject_areNull() {
		ObjectNode settings = NODES.objectNode().put("cpus", Double.POSITIVE_INFINITY).put("share", 0.5);
		settings.putObject("limits").put("memory", Double.NEGATIVE_INFINITY);
		settings.putArray("weights").add(Double.NaN).add(1.5);

		ObjectNode written = RecordCodec.write(new Loose(NODES.nullNode(), settings, List.of()));

		assertThat(written.get("settings")).isEqualTo(CodecTestSupport
			.json("{\"cpus\": null, \"share\": 0.5, \"limits\": {\"memory\": null}, \"weights\": [null, 1.5]}"));
	}

	@Test
	void write_nonFiniteNumberAsAnAnyValue_isNull() {
		ObjectNode written = RecordCodec.write(new Loose(NODES.numberNode(Double.NaN), NODES.objectNode(), List.of()));

		assertThat(written.get("anything")).isEqualTo(NODES.nullNode());
	}

	@Test
	void write_anyObject_leavesTheRecordsOwnObjectAsItWas() {
		ObjectNode settings = NODES.objectNode().put("cpus", Double.POSITIVE_INFINITY);

		RecordCodec.write(new Loose(NODES.nullNode(), settings, List.of()));

		assertThat(settings.get("cpus").doubleValue()).isEqualTo(Double.POSITIVE_INFINITY);
	}

	@Test
	void write_nonFiniteNumberInAFloatField_isKept() {
		ObjectNode written = RecordCodec
			.write(new Loose(NODES.nullNode(), NODES.objectNode(), List.of(Double.POSITIVE_INFINITY)));

		assertThat(written.get("ratios").get(0).doubleValue()).isEqualTo(Double.POSITIVE_INFINITY);
	}

}
