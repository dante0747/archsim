package io.archsim.model.text;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/** Python's text for values, as {@code repr()}, {@code str()} and {@code str.title()} write it (Python 3.12). */
class PythonTextTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest(name = "repr({0}) is {1}")
    @CsvSource(delimiter = '|', textBlock = """
            0.0                     | 0.0
            -0.0                    | -0.0
            0.25                    | 0.25
            100                     | 100.0
            0.0001                  | 0.0001
            0.00001                 | 1e-05
            1e15                    | 1000000000000000.0
            1e16                    | 1e+16
            1e22                    | 1e+22
            123456789               | 123456789.0
            2.675                   | 2.675
            -12.345                 | -12.345
            0.3333333333333333      | 0.3333333333333333
            1e-7                    | 1e-07
            1.7976931348623157e308  | 1.7976931348623157e+308
            0.30000000000000004     | 0.30000000000000004
            12345.678               | 12345.678
            """)
    void a_float_reads_as_python_writes_it(double value, String python) {
        assertThat(PythonText.repr(value)).isEqualTo(python);
    }

    @Property
    void a_floats_repr_reads_back_as_the_same_float(@ForAll double value) {
        if (Double.isFinite(value)) {
            assertThat(Double.parseDouble(PythonText.repr(value))).isEqualTo(value);
        }
    }

    @Test
    void the_special_floats_are_inf_and_nan() {
        assertThat(PythonText.repr(Double.POSITIVE_INFINITY)).isEqualTo("inf");
        assertThat(PythonText.repr(Double.NEGATIVE_INFINITY)).isEqualTo("-inf");
        assertThat(PythonText.repr(Double.NaN)).isEqualTo("nan");
    }

    @ParameterizedTest(name = "str({0}) is {1}")
    @CsvSource(delimiter = '|', textBlock = """
            null                 | None
            true                 | True
            5                    | 5
            1.5                  | 1.5
            "text"               | text
            ["a", 1, null]       | ['a', 1, None]
            {"a": {"b": false}}  | {'a': {'b': False}}
            """)
    void a_json_value_reads_as_python_writes_what_json_loads_gives(String json, String python) {
        assertThat(PythonText.str(JSON.readTree(json))).isEqualTo(python);
    }

    @ParameterizedTest(name = "repr of {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            plain  | 'plain'
            it's   | "it's"
            a"b    | 'a"b'
            """)
    void a_string_is_quoted_as_python_quotes_it(String text, String python) {
        assertThat(PythonText.repr(text)).isEqualTo(python);
    }

    @Test
    void a_control_character_is_escaped() {
        assertThat(PythonText.repr("tab\there")).isEqualTo("'tab\\there'");
    }

    @ParameterizedTest(name = "{0}.title() is {1}")
    @CsvSource({"latencyP95Ms, Latencyp95Ms", "snapshotSha256, Snapshotsha256", "autoSize, Autosize", "p95, P95", "x, X"
    })
    void title_capitalizes_each_run_of_letters(String text, String title) {
        assertThat(PythonText.title(text)).isEqualTo(title);
    }
}
