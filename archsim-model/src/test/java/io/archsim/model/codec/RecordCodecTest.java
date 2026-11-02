package io.archsim.model.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.archsim.model.text.CanonicalJson;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The codec reads JSON as pydantic does: the same coercions, the same errors in the same order
 * with the same messages; it writes the JSON and the JSON Schema pydantic writes. Every expected
 * message and schema here is pydantic 2's.
 */
class RecordCodecTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    enum Mode implements JsonEnum {
        AUTO("auto"),
        STEADY("steady"),
        RAMP("ramp");

        private final String json;

        Mode(String json) {
            this.json = json;
        }

        @Override
        public String json() {
            return json;
        }
    }

    record Sample(
            @Default("Untitled") @MinLength(1) @MaxLength(10)
            String name,

            @Default("1.0") @Gt(0) @Le(600_000) double rate,
            @Default("2") @Ge(1) @Le(10) int count,
            @Default("false") boolean enabled,
            @Ge(0.1) @Nullable Double share,
            @Default("[]") @MaxItems(2) List<@Matches("^[a-z]+$") String> tags,
            @Default("auto") Mode mode) {}

    record Part(String id, int size, @Default("0.5") double readRatio) {}

    @Tagged("kind")
    sealed interface Figure permits Circle, Square {}

    @Tag("circle")
    record Circle(@Gt(0) double radius) implements Figure {}

    @Tag("square")
    record Square(@Gt(0) double side) implements Figure {}

    record Drawing(Figure figure) {}

    record Window(double start, double end) {
        Window {
            if (end <= start) {
                throw new IllegalArgumentException("end must come after start");
            }
        }
    }

    @AsArray
    record Point(double t, double value) {}

    record Curve(@MinItems(2) List<Point> points) {}

    record Settings(
            @Default("auto") @OmitWhenDefault Mode mode,
            @OmitWhenDefault @Nullable Double extra) {}

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static List<BindingError> errors(String text, Class<?> type) {
        return catchThrowableOfType(BindingException.class, () -> RecordCodec.read(json(text), type))
                .errors();
    }

    private static BindingError error(String type, String location, String message) {
        List<Object> parts = new ArrayList<>();
        if (!location.isEmpty()) {
            for (String part : location.split("[.]", -1)) {
                parts.add(part.chars().allMatch(Character::isDigit) ? Integer.valueOf(part) : part);
            }
        }
        return new BindingError(type, new Location(parts), message);
    }

    @Nested
    @DisplayName("Reading")
    class Reading {

        @Test
        void fields_left_out_take_their_defaults() {
            assertThat(RecordCodec.read(json("{}"), Sample.class))
                    .isEqualTo(new Sample("Untitled", 1.0, 2, false, null, List.of(), Mode.AUTO));
        }

        @Test
        void a_required_field_left_out_is_reported_as_required() {
            assertThat(errors("{}", Part.class))
                    .containsExactly(
                            error("missing", "id", "Field required"), error("missing", "size", "Field required"));
        }

        @Test
        void every_error_is_reported_at_once_fields_in_order_then_unknown_keys_in_the_inputs_order() {
            assertThat(errors("""
                            {"zz": 1, "count": 0, "name": "", "aa": 2, "rate": "fast"}""", Sample.class))
                    .containsExactly(
                            error("string_too_short", "name", "String should have at least 1 character"),
                            error(
                                    "float_parsing",
                                    "rate",
                                    "Input should be a valid number, unable to parse string as a number"),
                            error("greater_than_equal", "count", "Input should be greater than or equal to 1"),
                            error("extra_forbidden", "zz", "Extra inputs are not permitted"),
                            error("extra_forbidden", "aa", "Extra inputs are not permitted"));
        }

        @Test
        void a_field_may_be_given_by_its_python_name_too() {
            assertThat(RecordCodec.read(json("{\"id\": \"a\", \"size\": 1, \"read_ratio\": 0.25}"), Part.class))
                    .isEqualTo(new Part("a", 1, 0.25));
        }

        @Test
        void given_both_names_the_json_name_wins_and_the_python_name_is_unknown() {
            assertThat(errors("{\"id\": \"a\", \"size\": 1, \"read_ratio\": 0.25, \"readRatio\": 0.5}", Part.class))
                    .containsExactly(error("extra_forbidden", "read_ratio", "Extra inputs are not permitted"));
        }

        @ParameterizedTest(name = "{0} reads as {1}")
        @CsvSource(delimiter = '|', textBlock = """
                {"rate": "1.5"}        | 1.5
                {"rate": " 2 "}        | 2.0
                {"rate": "1_000"}      | 1000.0
                {"rate": true}         | 1.0
                {"rate": 7}            | 7.0
                """)
        void a_number_may_be_a_numeric_string_a_boolean_or_an_integer(String input, double rate) {
            assertThat(RecordCodec.read(json(input), Sample.class).rate()).isEqualTo(rate);
        }

        @ParameterizedTest(name = "{0} reads as {1}")
        @CsvSource(delimiter = '|', textBlock = """
                {"count": 5.0}        | 5
                {"count": "5"}        | 5
                {"count": " 6 "}      | 6
                {"count": "7.0"}      | 7
                {"count": true}       | 1
                """)
        void an_integer_may_be_a_whole_float_or_an_integer_string(String input, int count) {
            assertThat(RecordCodec.read(json(input), Sample.class).count()).isEqualTo(count);
        }

        @ParameterizedTest(name = "{0}: {1}")
        @CsvSource(delimiter = '|', textBlock = """
                {"count": 5.5}    | int_from_float | Input should be a valid integer, got a number with a fractional part
                {"count": "5.5"}  | int_parsing    | Input should be a valid integer, unable to parse string as an integer
                {"count": "1e3"}  | int_parsing    | Input should be a valid integer, unable to parse string as an integer
                {"count": null}   | int_type       | Input should be a valid integer
                {"count": 1e20}   | int_parsing_size | Unable to parse input string as an integer, exceeded maximum size
                """)
        void an_integer_with_a_fraction_or_no_integer_at_all_is_refused(String input, String type, String message) {
            assertThat(errors(input, Sample.class)).containsExactly(error(type, "count", message));
        }

        @ParameterizedTest(name = "{0} reads as {1}")
        @CsvSource(delimiter = '|', textBlock = """
                {"enabled": "yes"}   | true
                {"enabled": "OFF"}   | false
                {"enabled": 1}       | true
                {"enabled": 0.0}     | false
                """)
        void a_boolean_may_be_one_of_pydantics_words_or_zero_or_one(String input, boolean enabled) {
            assertThat(RecordCodec.read(json(input), Sample.class).enabled()).isEqualTo(enabled);
        }

        @Test
        void other_booleans_are_refused_as_pydantic_refuses_them() {
            assertThat(errors("{\"enabled\": 2}", Sample.class))
                    .containsExactly(error(
                            "bool_parsing", "enabled", "Input should be a valid boolean, unable to interpret input"));
            assertThat(errors("{\"enabled\": 0.5}", Sample.class))
                    .containsExactly(error("bool_type", "enabled", "Input should be a valid boolean"));
        }

        @Test
        void a_bound_is_written_as_pydantic_writes_it() {
            assertThat(errors("{\"rate\": 0, \"share\": 0.05}", Sample.class))
                    .containsExactly(
                            error("greater_than", "rate", "Input should be greater than 0"),
                            error("greater_than_equal", "share", "Input should be greater than or equal to 0.1"));
            assertThat(errors("{\"rate\": 700000}", Sample.class))
                    .containsExactly(error("less_than_equal", "rate", "Input should be less than or equal to 600000"));
        }

        @Test
        void a_string_is_only_a_string() {
            assertThat(errors("{\"name\": 5}", Sample.class))
                    .containsExactly(error("string_type", "name", "Input should be a valid string"));
        }

        @Test
        void a_pattern_must_match_up_to_the_very_end_of_the_text() {
            assertThat(errors("{\"tags\": [\"ok\", \"no\\n\"]}", Sample.class))
                    .containsExactly(
                            error("string_pattern_mismatch", "tags.1", "String should match pattern '^[a-z]+$'"));
        }

        @Test
        void a_list_longer_than_its_maximum_is_reported_as_that_alone() {
            assertThat(errors("{\"tags\": [1, 2, 3]}", Sample.class))
                    .containsExactly(
                            error("too_long", "tags", "List should have at most 2 items after validation, not 3"));
        }

        @Test
        void a_literal_lists_what_it_accepts() {
            assertThat(errors("{\"mode\": \"fast\"}", Sample.class))
                    .containsExactly(error("literal_error", "mode", "Input should be 'auto', 'steady' or 'ramp'"));
        }

        @Test
        void a_tuple_takes_exactly_its_items() {
            assertThat(RecordCodec.read(json("{\"points\": [[0, 1], [5, 2.5]]}"), Curve.class))
                    .isEqualTo(new Curve(List.of(new Point(0, 1), new Point(5, 2.5))));
            assertThat(errors("{\"points\": [[0, 1], [1]]}", Curve.class))
                    .containsExactly(error("missing", "points.1.1", "Field required"));
            assertThat(errors("{\"points\": [[0, 1], [1, 2, 3]]}", Curve.class))
                    .containsExactly(
                            error("too_long", "points.1", "Tuple should have at most 2 items after validation, not 3"));
            assertThat(errors("{\"points\": [[0, 1]]}", Curve.class))
                    .containsExactly(
                            error("too_short", "points", "List should have at least 2 items after validation, not 1"));
        }

        @Test
        void a_records_own_check_fails_as_a_value_error_at_the_record() {
            assertThat(errors("{\"start\": 5, \"end\": 1}", Window.class))
                    .containsExactly(error("value_error", "", "Value error, end must come after start"));
        }

        @Test
        void a_nested_record_must_be_an_object() {
            assertThat(errors("{\"figure\": 5}", Drawing.class))
                    .containsExactly(error(
                            "model_attributes_type",
                            "figure",
                            "Input should be a valid dictionary or object to extract fields from"));
        }
    }

    @Nested
    @DisplayName("Tagged unions")
    class TaggedUnions {

        @Test
        void the_tag_picks_the_record() {
            assertThat(RecordCodec.read(json("{\"figure\": {\"kind\": \"square\", \"side\": 2}}"), Drawing.class))
                    .isEqualTo(new Drawing(new Square(2)));
        }

        @Test
        void an_error_inside_a_record_is_located_under_its_tag() {
            assertThat(errors("{\"figure\": {\"kind\": \"circle\", \"radius\": -1}}", Drawing.class))
                    .containsExactly(error("greater_than", "figure.circle.radius", "Input should be greater than 0"));
        }

        @Test
        void an_unknown_tag_lists_the_tags_there_are() {
            assertThat(errors("{\"figure\": {\"kind\": 5}}", Drawing.class))
                    .containsExactly(error(
                            "union_tag_invalid",
                            "figure",
                            "Input tag '5' found using 'kind' does not match any of the expected tags: 'circle',"
                                    + " 'square'"));
        }

        @Test
        void a_missing_tag_is_reported_as_such() {
            assertThat(errors("{\"figure\": {\"radius\": 1}}", Drawing.class))
                    .containsExactly(
                            error("union_tag_not_found", "figure", "Unable to extract tag using discriminator 'kind'"));
        }
    }

    @Nested
    @DisplayName("Writing")
    class Writing {

        @Test
        void a_record_is_written_with_every_field_in_order() {
            assertThat(RecordCodec.write(new Sample("x", 2.0, 3, true, null, List.of("a"), Mode.RAMP)))
                    .isEqualTo(json("""
                            {"name": "x", "rate": 2.0, "count": 3, "enabled": true, "share": null,
                             "tags": ["a"], "mode": "ramp"}"""));
        }

        @Test
        void a_tagged_record_is_written_with_its_tag() {
            assertThat(RecordCodec.write(new Circle(1.5))).isEqualTo(json("{\"kind\": \"circle\", \"radius\": 1.5}"));
        }

        @Test
        void a_field_marked_so_is_left_out_while_it_has_its_default() {
            assertThat(RecordCodec.write(new Settings(Mode.AUTO, null))).isEqualTo(json("{}"));
            assertThat(RecordCodec.write(new Settings(Mode.RAMP, 2.0)))
                    .isEqualTo(json("{\"mode\": \"ramp\", \"extra\": 2.0}"));
        }

        @Test
        void an_array_record_is_written_as_an_array() {
            assertThat(RecordCodec.write(new Curve(List.of(new Point(0, 1), new Point(2, 3)))))
                    .isEqualTo(json("{\"points\": [[0.0, 1.0], [2.0, 3.0]]}"));
        }
    }

    @Nested
    @DisplayName("JSON Schema")
    class Schema {

        /** The schemas compared as Python's {@code json.dumps(sort_keys=True)} writes them. */
        private static void assertSchema(Class<? extends Record> type, String pydantic) {
            assertThat(CanonicalJson.write(RecordCodec.jsonSchema(type)))
                    .isEqualTo(CanonicalJson.write(json(pydantic)));
        }

        @Test
        void a_property_is_titled_as_python_titles_its_json_name() {
            assertSchema(Part.class, """
                    {"additionalProperties": false, "title": "Part", "type": "object",
                     "properties": {
                       "id": {"title": "Id", "type": "string"},
                       "size": {"title": "Size", "type": "integer"},
                       "readRatio": {"default": 0.5, "title": "Readratio", "type": "number"}},
                     "required": ["id", "size"]}""");
        }

        @Test
        void bounds_defaults_and_nullable_fields_are_written_as_pydantic_writes_them_and_a_list_default_is_hidden() {
            assertSchema(Sample.class, """
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
        void a_tagged_union_is_a_discriminated_one_of_with_each_record_under_defs() {
            assertSchema(Drawing.class, """
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
        void an_array_record_is_a_tuple() {
            assertSchema(Curve.class, """
                    {"additionalProperties": false, "title": "Curve", "type": "object",
                     "properties": {
                       "points": {
                         "items": {"maxItems": 2, "minItems": 2, "prefixItems": [{"type": "number"}, {"type": "number"}],
                                   "type": "array"},
                         "minItems": 2, "title": "Points", "type": "array"}},
                     "required": ["points"]}""");
        }
    }
}
