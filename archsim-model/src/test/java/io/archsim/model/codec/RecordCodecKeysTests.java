package io.archsim.model.codec;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.archsim.model.codec.SampleRecords.Box;
import io.archsim.model.codec.SampleRecords.Budget;
import io.archsim.model.codec.SampleRecords.Halo;
import io.archsim.model.codec.SampleRecords.Mode;
import io.archsim.model.codec.SampleRecords.Plate;
import io.archsim.model.codec.SampleRecords.Round;
import io.archsim.model.codec.SampleRecords.Sketch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The codec's shapes told apart or keyed by a key: a union whose record is picked by
 * which key an object has, and a map keyed by a {@link JsonEnum}. Every expected message
 * and schema here is pydantic 2's.
 */
class RecordCodecKeysTests {

	@Nested
	@DisplayName("Unions told apart by a key")
	class UnionsByKey {

		@Test
		void read_keyOfARecord_picksIt() {
			assertThat(RecordCodec.read(CodecTestSupport.json("{\"outline\": {\"radius\": 1}}"), Sketch.class))
				.isEqualTo(new Sketch(new Round(1)));
		}

		@Test
		void read_pythonNameOfAKey_picksItsRecordToo() {
			assertThat(RecordCodec.read(CodecTestSupport.json("{\"outline\": {\"side_length\": 2}}"), Sketch.class))
				.isEqualTo(new Sketch(new Box(2)));
		}

		@Test
		void read_errorInsideThePickedRecord_isLocatedUnderItsTag() {
			assertThat(CodecTestSupport.errors("{\"outline\": {\"radius\": -1}}", Sketch.class)).containsExactly(
					CodecTestSupport.error("greater_than", "outline.radius.radius", "Input should be greater than 0"));
		}

		@Test
		void read_keysOfTwoRecords_picksTheFirstAndRefusesTheOthersKey() {
			assertThat(CodecTestSupport.errors("{\"outline\": {\"radius\": 1, \"sideLength\": 2}}", Sketch.class))
				.containsExactly(CodecTestSupport.error("extra_forbidden", "outline.radius.sideLength",
						"Extra inputs are not permitted"));
		}

		@ParameterizedTest
		@ValueSource(strings = { "{\"width\": 1}", "5", "null" })
		void read_noRecordsKey_isTheUnionsOwnError(String outline) {
			assertThat(CodecTestSupport.errors("{\"outline\": " + outline + "}", Sketch.class)).containsExactly(
					CodecTestSupport.error("outline_kind", "outline", "an outline is {radius} or {sideLength}"));
		}

		@Test
		void read_unionThatDoesNotListEveryRecord_isRefused() {
			assertThatIllegalArgumentException()
				.isThrownBy(() -> RecordCodec.read(CodecTestSupport.json("{\"shape\": {\"radius\": 1}}"), Halo.class))
				.withMessageEndingWith("must list each of its permitted records once as members");
		}

		@Test
		void read_keyTagNamingNoField_isRefused() {
			assertThatIllegalArgumentException()
				.isThrownBy(() -> RecordCodec.read(CodecTestSupport.json("{\"shape\": {\"width\": 1}}"), Plate.class))
				.withMessageEndingWith("has no field diameter");
		}

		@Test
		void write_recordOfTheUnion_hasNoTag() {
			assertThat(RecordCodec.write(new Sketch(new Box(2))))
				.isEqualTo(CodecTestSupport.json("{\"outline\": {\"sideLength\": 2.0}}"));
		}

	}

	@Nested
	@DisplayName("Maps keyed by a choice")
	class MapsByChoice {

		@Test
		void read_keysOfTheChoice_areItsConstants() {
			assertThat(
					RecordCodec.read(CodecTestSupport.json("{\"limits\": {\"auto\": 1, \"ramp\": 2}}"), Budget.class))
				.isEqualTo(new Budget(Map.of(Mode.AUTO, 1.0, Mode.RAMP, 2.0)));
		}

		@Test
		void read_keyNotOfTheChoice_isLocatedAtTheKeyAndItsValueIsStillRead() {
			assertThat(
					CodecTestSupport.errors("{\"limits\": {\"fast\": 1, \"auto\": \"x\", \"ramp\": 2}}", Budget.class))
				.containsExactly(
						CodecTestSupport.error("literal_error", "limits.fast.[key]",
								"Input should be 'auto', 'steady' or 'ramp'"),
						CodecTestSupport.error("float_parsing", "limits.auto",
								"Input should be a valid number, unable to parse string as a number"));
		}

		@Test
		void write_map_isKeyedByTheConstantsStrings() {
			assertThat(RecordCodec.write(new Budget(Map.of(Mode.STEADY, 1.5))))
				.isEqualTo(CodecTestSupport.json("{\"limits\": {\"steady\": 1.5}}"));
		}

	}

	@Nested
	@DisplayName("JSON Schema")
	class Schema {

		@Test
		void jsonSchema_unionByKey_isOneOfWithoutADiscriminator() {
			CodecTestSupport.assertSchema(Sketch.class, """
					{"additionalProperties": false, "title": "Sketch", "type": "object",
					 "properties": {
					   "outline": {"oneOf": [{"$ref": "#/$defs/Round"}, {"$ref": "#/$defs/Box"}], "title": "Outline"}},
					 "required": ["outline"],
					 "$defs": {
					   "Box": {"additionalProperties": false, "title": "Box", "type": "object",
					           "properties": {"sideLength": {"title": "Sidelength", "type": "number"}},
					           "required": ["sideLength"]},
					   "Round": {"additionalProperties": false, "title": "Round", "type": "object",
					             "properties": {"radius": {"exclusiveMinimum": 0, "title": "Radius", "type": "number"}},
					             "required": ["radius"]}}}""");
		}

		@Test
		void jsonSchema_mapKeyedByAChoice_namesItsKeys() {
			CodecTestSupport.assertSchema(Budget.class, """
					{"additionalProperties": false, "title": "Budget", "type": "object",
					 "properties": {
					   "limits": {"additionalProperties": {"type": "number"},
					              "propertyNames": {"enum": ["auto", "steady", "ramp"]}, "title": "Limits",
					              "type": "object"}},
					 "required": ["limits"]}""");
		}

	}

}
