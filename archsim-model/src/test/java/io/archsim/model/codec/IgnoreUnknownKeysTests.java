package io.archsim.model.codec;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.SampleRecords.Request;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A record that ignores unknown keys ({@link IgnoreUnknownKeys}) reads as a pydantic
 * model with {@code extra="ignore"}: it leaves them out, never encodes them, and says
 * nothing of them in its JSON Schema; a record it holds keeps its own rule.
 */
class IgnoreUnknownKeysTests {

	@Test
	void read_unknownKeys_areLeftOut() {
		Request request = RecordCodec.read(CodecTestSupport.json("{\"name\": \"a\", \"colour\": \"blue\"}"),
				Request.class);

		assertThat(request).isEqualTo(new Request("a", null));
	}

	@Test
	void read_unknownKeyHoldingALoneSurrogate_isLeftOutUnread() {
		ObjectNode input = (ObjectNode) CodecTestSupport.json("{\"name\": \"a\"}");
		input.put(Character.toString(Character.MIN_HIGH_SURROGATE), 1);

		Request request = RecordCodec.read(input, Request.class);

		assertThat(request).isEqualTo(new Request("a", null));
	}

	@Test
	void read_unknownKeysOfARecordItHolds_areRefusedByThatRecordsOwnRule() {
		String input = "{\"name\": \"a\", \"part\": {\"id\": \"p\", \"size\": 1, \"zz\": 2}}";

		List<BindingError> errors = CodecTestSupport.errors(input, Request.class);

		assertThat(errors)
			.containsExactly(CodecTestSupport.error("extra_forbidden", "part.zz", "Extra inputs are not permitted"));
	}

	@Test
	void jsonSchema_recordThatIgnoresUnknownKeys_isAsPydanticWritesIt() {
		CodecTestSupport.assertSchema(Request.class, """
				{"title": "Request", "type": "object",
				 "properties": {
				   "name": {"title": "Name", "type": "string"},
				   "part": {"anyOf": [{"$ref": "#/$defs/Part"}, {"type": "null"}], "default": null}},
				 "required": ["name"],
				 "$defs": {
				   "Part": {"additionalProperties": false, "title": "Part", "type": "object",
				            "properties": {
				              "id": {"title": "Id", "type": "string"},
				              "size": {"title": "Size", "type": "integer"},
				              "readRatio": {"default": 0.5, "title": "Readratio", "type": "number"}},
				            "required": ["id", "size"]}}}""");
	}

}
