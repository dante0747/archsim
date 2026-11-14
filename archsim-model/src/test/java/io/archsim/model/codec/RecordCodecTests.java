package io.archsim.model.codec;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.archsim.model.codec.SampleRecords.Circle;
import io.archsim.model.codec.SampleRecords.Curve;
import io.archsim.model.codec.SampleRecords.Drawing;
import io.archsim.model.codec.SampleRecords.Mode;
import io.archsim.model.codec.SampleRecords.Part;
import io.archsim.model.codec.SampleRecords.Point;
import io.archsim.model.codec.SampleRecords.Sample;
import io.archsim.model.codec.SampleRecords.Settings;
import io.archsim.model.codec.SampleRecords.Square;
import io.archsim.model.codec.SampleRecords.Threads;
import io.archsim.model.codec.SampleRecords.Window;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The codec reads JSON as pydantic does: the same coercions, the same errors in the same
 * order with the same messages; it writes the JSON and the JSON Schema pydantic writes.
 * Every expected message and schema here is pydantic 2's.
 */
class RecordCodecTests {

	@Nested
	@DisplayName("Reading")
	class Reading {

		@Test
		void read_fieldsLeftOut_takeTheirDefaults() {
			assertThat(RecordCodec.read(CodecTestSupport.json("{}"), Sample.class))
				.isEqualTo(new Sample("Untitled", 1.0, 2, false, null, List.of(), Mode.AUTO));
		}

		@Test
		void read_requiredFieldLeftOut_isReportedAsRequired() {
			assertThat(CodecTestSupport.errors("{}", Part.class)).containsExactly(
					CodecTestSupport.error("missing", "id", "Field required"),
					CodecTestSupport.error("missing", "size", "Field required"));
		}

		@Test
		void read_severalErrors_areReportedAtOnceFieldsFirstThenUnknownKeys() {
			assertThat(CodecTestSupport.errors("""
					{"zz": 1, "count": 0, "name": "", "aa": 2, "rate": "fast"}""", Sample.class)).containsExactly(
					CodecTestSupport.error("string_too_short", "name", "String should have at least 1 character"),
					CodecTestSupport.error("float_parsing", "rate",
							"Input should be a valid number, unable to parse string as a number"),
					CodecTestSupport.error("greater_than_equal", "count", "Input should be greater than or equal to 1"),
					CodecTestSupport.error("extra_forbidden", "zz", "Extra inputs are not permitted"),
					CodecTestSupport.error("extra_forbidden", "aa", "Extra inputs are not permitted"));
		}

		@Test
		void read_fieldByItsPythonName_isAccepted() {
			assertThat(RecordCodec.read(CodecTestSupport.json("{\"id\": \"a\", \"size\": 1, \"read_ratio\": 0.25}"),
					Part.class))
				.isEqualTo(new Part("a", 1, 0.25));
		}

		@Test
		void read_bothNamesOfAField_takesTheJsonNameAndRefusesThePythonOne() {
			assertThat(CodecTestSupport.errors("{\"id\": \"a\", \"size\": 1, \"read_ratio\": 0.25, \"readRatio\": 0.5}",
					Part.class))
				.containsExactly(
						CodecTestSupport.error("extra_forbidden", "read_ratio", "Extra inputs are not permitted"));
		}

		@ParameterizedTest(name = "{0} reads as {1}")
		@CsvSource(delimiter = '|', textBlock = """
				{"rate": "1.5"}        | 1.5
				{"rate": " 2 "}        | 2.0
				{"rate": "1_000"}      | 1000.0
				{"rate": true}         | 1.0
				{"rate": 7}            | 7.0
				""")
		void read_numberAsNumericStringBooleanOrInteger_isCoerced(String input, double rate) {
			assertThat(RecordCodec.read(CodecTestSupport.json(input), Sample.class).rate()).isEqualTo(rate);
		}

		@ParameterizedTest(name = "{0} reads as {1}")
		@CsvSource(delimiter = '|', textBlock = """
				{"count": 5.0}        | 5
				{"count": "5"}        | 5
				{"count": " 6 "}      | 6
				{"count": "7.0"}      | 7
				{"count": true}       | 1
				""")
		void read_integerAsWholeFloatOrIntegerString_isCoerced(String input, int count) {
			assertThat(RecordCodec.read(CodecTestSupport.json(input), Sample.class).count()).isEqualTo(count);
		}

		@ParameterizedTest(name = "count {0}: {1}")
		@CsvSource(delimiter = '|', textBlock = """
				5.5    | int_from_float   | Input should be a valid integer, got a number with a fractional part
				"5.5"  | int_parsing      | Input should be a valid integer, unable to parse string as an integer
				"1e3"  | int_parsing      | Input should be a valid integer, unable to parse string as an integer
				null   | int_type         | Input should be a valid integer
				1e20   | int_parsing_size | Unable to parse input string as an integer, exceeded maximum size
				""")
		void read_integerWithFractionOrNoIntegerAtAll_isRefused(String count, String type, String message) {
			String input = "{\"count\": " + count + "}";
			assertThat(CodecTestSupport.errors(input, Sample.class))
				.containsExactly(CodecTestSupport.error(type, "count", message));
		}

		@ParameterizedTest(name = "{0} reads as {1}")
		@CsvSource(delimiter = '|', textBlock = """
				{"enabled": "yes"}   | true
				{"enabled": "OFF"}   | false
				{"enabled": 1}       | true
				{"enabled": 0.0}     | false
				""")
		void read_booleanAsPydanticWordOrZeroOrOne_isCoerced(String input, boolean enabled) {
			assertThat(RecordCodec.read(CodecTestSupport.json(input), Sample.class).enabled()).isEqualTo(enabled);
		}

		@Test
		void read_otherBooleans_areRefusedAsPydanticRefusesThem() {
			assertThat(CodecTestSupport.errors("{\"enabled\": 2}", Sample.class)).containsExactly(CodecTestSupport
				.error("bool_parsing", "enabled", "Input should be a valid boolean, unable to interpret input"));
			assertThat(CodecTestSupport.errors("{\"enabled\": 0.5}", Sample.class))
				.containsExactly(CodecTestSupport.error("bool_type", "enabled", "Input should be a valid boolean"));
		}

		@Test
		void read_valueOutOfBounds_isReportedAsPydanticWritesIt() {
			assertThat(CodecTestSupport.errors("{\"rate\": 0, \"share\": 0.05}", Sample.class)).containsExactly(
					CodecTestSupport.error("greater_than", "rate", "Input should be greater than 0"), CodecTestSupport
						.error("greater_than_equal", "share", "Input should be greater than or equal to 0.1"));
			assertThat(CodecTestSupport.errors("{\"rate\": 700000}", Sample.class)).containsExactly(
					CodecTestSupport.error("less_than_equal", "rate", "Input should be less than or equal to 600000"));
		}

		@Test
		void read_numberForString_isRefused() {
			assertThat(CodecTestSupport.errors("{\"name\": 5}", Sample.class))
				.containsExactly(CodecTestSupport.error("string_type", "name", "Input should be a valid string"));
		}

		@Test
		void read_patternMatchedBeforeTheEnd_isRefused() {
			assertThat(CodecTestSupport.errors("{\"tags\": [\"ok\", \"no\\n\"]}", Sample.class))
				.containsExactly(CodecTestSupport.error("string_pattern_mismatch", "tags.1",
						"String should match pattern '^[a-z]+$'"));
		}

		@Test
		void read_listLongerThanItsMaximum_isReportedAsThatAlone() {
			assertThat(CodecTestSupport.errors("{\"tags\": [1, 2, 3]}", Sample.class)).containsExactly(CodecTestSupport
				.error("too_long", "tags", "List should have at most 2 items after validation, not 3"));
		}

		@ParameterizedTest(name = "{0} reads as {1}")
		@CsvSource(delimiter = '|', textBlock = """
				{"perCore": 2}       | 2
				{"perCore": 4.0}     | 4
				{"perCore": true}    | 1
				""")
		void read_choiceAsIntegerWholeFloatOrBoolean_isAccepted(String input, int perCore) {
			assertThat(RecordCodec.read(CodecTestSupport.json(input), Threads.class).perCore()).isEqualTo(perCore);
		}

		@ParameterizedTest(name = "perCore {0}")
		@ValueSource(strings = { "3", "1.5", "\"2\"" })
		void read_integerNotAChoice_listsTheChoicesInWrittenOrder(String perCore) {
			assertThat(CodecTestSupport.errors("{\"perCore\": " + perCore + "}", Threads.class))
				.containsExactly(CodecTestSupport.error("literal_error", "perCore", "Input should be 4, 1 or 2"));
		}

		@Test
		void read_unknownLiteral_listsWhatItAccepts() {
			assertThat(CodecTestSupport.errors("{\"mode\": \"fast\"}", Sample.class)).containsExactly(
					CodecTestSupport.error("literal_error", "mode", "Input should be 'auto', 'steady' or 'ramp'"));
		}

		@Test
		void read_tuple_takesExactlyItsItems() {
			assertThat(RecordCodec.read(CodecTestSupport.json("{\"points\": [[0, 1], [5, 2.5]]}"), Curve.class))
				.isEqualTo(new Curve(List.of(new Point(0, 1), new Point(5, 2.5))));
			assertThat(CodecTestSupport.errors("{\"points\": [[0, 1], [1]]}", Curve.class))
				.containsExactly(CodecTestSupport.error("missing", "points.1.1", "Field required"));
			assertThat(CodecTestSupport.errors("{\"points\": [[0, 1], [1, 2, 3]]}", Curve.class))
				.containsExactly(CodecTestSupport.error("too_long", "points.1",
						"Tuple should have at most 2 items after validation, not 3"));
			assertThat(CodecTestSupport.errors("{\"points\": [[0, 1]]}", Curve.class)).containsExactly(CodecTestSupport
				.error("too_short", "points", "List should have at least 2 items after validation, not 1"));
		}

		@Test
		void read_recordsOwnCheckFailing_isValueErrorAtTheRecord() {
			assertThat(CodecTestSupport.errors("{\"start\": 5, \"end\": 1}", Window.class))
				.containsExactly(CodecTestSupport.error("value_error", "", "Value error, end must come after start"));
		}

		@Test
		void read_nestedRecordNotAnObject_isRefused() {
			assertThat(CodecTestSupport.errors("{\"figure\": 5}", Drawing.class))
				.containsExactly(CodecTestSupport.error("model_attributes_type", "figure",
						"Input should be a valid dictionary or object to extract fields from"));
		}

	}

	@Nested
	@DisplayName("Tagged unions")
	class TaggedUnions {

		@Test
		void read_tag_picksTheRecord() {
			assertThat(RecordCodec.read(CodecTestSupport.json("{\"figure\": {\"kind\": \"square\", \"side\": 2}}"),
					Drawing.class))
				.isEqualTo(new Drawing(new Square(2)));
		}

		@Test
		void read_errorInsideTaggedRecord_isLocatedUnderItsTag() {
			assertThat(CodecTestSupport.errors("{\"figure\": {\"kind\": \"circle\", \"radius\": -1}}", Drawing.class))
				.containsExactly(CodecTestSupport.error("greater_than", "figure.circle.radius",
						"Input should be greater than 0"));
		}

		@Test
		void read_unknownTag_listsTheTagsThereAre() {
			assertThat(CodecTestSupport.errors("{\"figure\": {\"kind\": 5}}", Drawing.class))
				.containsExactly(CodecTestSupport.error("union_tag_invalid", "figure",
						"Input tag '5' found using 'kind' does not match any of the expected tags: 'circle',"
								+ " 'square'"));
		}

		@Test
		void tag_aMemberOfATaggedInterface_isItsTag() {
			assertThat(RecordCodec.tag(new Square(2))).isEqualTo("square");
		}

		@Test
		void tag_aRecordWithoutATag_isRefused() {
			assertThatIllegalArgumentException().isThrownBy(() -> RecordCodec.tag(new Point(1, 2)))
				.withMessage("Point is not a tagged record");
		}

		@Test
		void read_missingTag_isReportedAsSuch() {
			assertThat(CodecTestSupport.errors("{\"figure\": {\"radius\": 1}}", Drawing.class))
				.containsExactly(CodecTestSupport.error("union_tag_not_found", "figure",
						"Unable to extract tag using discriminator 'kind'"));
		}

	}

	@Nested
	@DisplayName("Writing")
	class Writing {

		@Test
		void write_record_hasEveryFieldInOrder() {
			assertThat(RecordCodec.write(new Sample("x", 2.0, 3, true, null, List.of("a"), Mode.RAMP)))
				.isEqualTo(CodecTestSupport.json("""
						{"name": "x", "rate": 2.0, "count": 3, "enabled": true, "share": null,
						 "tags": ["a"], "mode": "ramp"}"""));
		}

		@Test
		void write_taggedRecord_hasItsTag() {
			assertThat(RecordCodec.write(new Circle(1.5)))
				.isEqualTo(CodecTestSupport.json("{\"kind\": \"circle\", \"radius\": 1.5}"));
		}

		@Test
		void write_fieldOmittedWhenDefault_isLeftOutWhileItHasItsDefault() {
			assertThat(RecordCodec.write(new Settings(Mode.AUTO, null))).isEqualTo(CodecTestSupport.json("{}"));
			assertThat(RecordCodec.write(new Settings(Mode.RAMP, 2.0)))
				.isEqualTo(CodecTestSupport.json("{\"mode\": \"ramp\", \"extra\": 2.0}"));
		}

		@Test
		void write_arrayRecord_isAnArray() {
			assertThat(RecordCodec.write(new Curve(List.of(new Point(0, 1), new Point(2, 3)))))
				.isEqualTo(CodecTestSupport.json("{\"points\": [[0.0, 1.0], [2.0, 3.0]]}"));
		}

	}

	@Nested
	@DisplayName("Defaults")
	class Defaults {

		@Test
		void defaults_aFieldLeftOutWhileItIsItsDefault_isItsDeclaredDefault() {
			assertThat(RecordCodec.defaults(Settings.class).path("mode").stringValue()).isEqualTo("auto");
		}

		@Test
		void defaults_aNullableFieldWithoutADefault_isNull() {
			assertThat(RecordCodec.defaults(Settings.class).get("extra").isNull()).isTrue();
		}

		@Test
		void defaults_aRequiredField_isLeftOut() {
			assertThat(RecordCodec.defaults(Part.class).propertyNames()).containsExactly("readRatio");
		}

	}

	@Nested
	@DisplayName("JSON Schema")
	class Schema {

		@Test
		void jsonSchema_property_isTitledAsPythonTitlesItsJsonName() {
			CodecTestSupport.assertSchema(Part.class, """
					{"additionalProperties": false, "title": "Part", "type": "object",
					 "properties": {
					   "id": {"title": "Id", "type": "string"},
					   "size": {"title": "Size", "type": "integer"},
					   "readRatio": {"default": 0.5, "title": "Readratio", "type": "number"}},
					 "required": ["id", "size"]}""");
		}

		@Test
		void jsonSchema_boundsDefaultsAndNullableFields_areAsPydanticWritesThemWithListDefaultHidden() {
			CodecTestSupport.assertSchema(Sample.class, """
					{"additionalProperties": false, "title": "Sample", "type": "object",
					 "properties": {
					   "name": {"default": "Untitled", "maxLength": 10, "minLength": 1, "title": "Name",
					            "type": "string"},
					   "rate": {"default": 1.0, "exclusiveMinimum": 0, "maximum": 600000, "title": "Rate",
					            "type": "number"},
					   "count": {"default": 2, "maximum": 10, "minimum": 1, "title": "Count", "type": "integer"},
					   "enabled": {"default": false, "title": "Enabled", "type": "boolean"},
					   "share": {"anyOf": [{"minimum": 0.1, "type": "number"}, {"type": "null"}], "default": null,
					             "title": "Share"},
					   "tags": {"items": {"pattern": "^[a-z]+$", "type": "string"}, "maxItems": 2, "title": "Tags",
					            "type": "array"},
					   "mode": {"default": "auto", "enum": ["auto", "steady", "ramp"], "title": "Mode",
					            "type": "string"}}}""");
		}

		@Test
		void jsonSchema_taggedUnion_isDiscriminatedOneOfWithEachRecordUnderDefs() {
			CodecTestSupport.assertSchema(Drawing.class, """
					{"additionalProperties": false, "title": "Drawing", "type": "object",
					 "properties": {
					   "figure": {
					     "discriminator": {"mapping": {"circle": "#/$defs/Circle", "square": "#/$defs/Square"},
					                       "propertyName": "kind"},
					     "oneOf": [{"$ref": "#/$defs/Circle"}, {"$ref": "#/$defs/Square"}],
					     "title": "Figure"}},
					 "required": ["figure"],
					 "$defs": {
					   "Circle": {"additionalProperties": false, "title": "Circle", "type": "object",
					              "properties": {
					                "kind": {"const": "circle", "default": "circle", "title": "Kind", "type": "string"},
					                "radius": {"exclusiveMinimum": 0, "title": "Radius", "type": "number"}},
					              "required": ["radius"]},
					   "Square": {"additionalProperties": false, "title": "Square", "type": "object",
					              "properties": {
					                "kind": {"const": "square", "default": "square", "title": "Kind", "type": "string"},
					                "side": {"exclusiveMinimum": 0, "title": "Side", "type": "number"}},
					              "required": ["side"]}}}""");
		}

		@Test
		void jsonSchema_integerChoices_areAnEnumInWrittenOrder() {
			CodecTestSupport.assertSchema(Threads.class, """
					{"additionalProperties": false, "title": "Threads", "type": "object",
					 "properties": {"perCore": {"enum": [4, 1, 2], "title": "Percore", "type": "integer"}},
					 "required": ["perCore"]}""");
		}

		@Test
		void jsonSchema_arrayRecord_isATuple() {
			CodecTestSupport.assertSchema(Curve.class, """
					{"additionalProperties": false, "title": "Curve", "type": "object",
					 "properties": {
					   "points": {
					     "items": {"maxItems": 2, "minItems": 2,
					               "prefixItems": [{"type": "number"}, {"type": "number"}], "type": "array"},
					     "minItems": 2, "title": "Points", "type": "array"}},
					 "required": ["points"]}""");
		}

	}

}
