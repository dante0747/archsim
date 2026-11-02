package io.archsim.model.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Python's {@code format(value, spec)}: the expected texts are Python 3.12's. */
class PythonFormatTest {

    @ParameterizedTest(name = "format({0}, \"{1}\") is \"{2}\"")
    @CsvSource(delimiter = '|', quoteCharacter = '`', ignoreLeadingAndTrailingWhitespace = false, textBlock = """
            0.25|g|0.25
            256.0|g|256
            0.0001234|g|0.0001234
            1000000.0|g|1e+06
            12345.678|g|12345.7
            123456789.0|g|1.23457e+08
            999999.5|g|1e+06
            -0.4|g|-0.4
            1e-07|g|1e-07
            12345.678|.4g|1.235e+04
            0.25|.4g|0.25
            0.25|,.0f|0
            2.675|,.0f|3
            12345.678|,.0f|12,346
            123456789.0|,.0f|123,456,789
            999999.5|,.1f|999,999.5
            -0.4|,.0f|-0
            0.25|.0%|25%
            2.675|.0%|268%
            0.125|.0%|12%
            0.8|.0%|80%
            0.29|.0%|29%
            0.999|.0%|100%
            2.675|.2f|2.67
            0.125|.2f|0.12
            12345.678|.2f|12345.68
            0.25|e|2.500000e-01
            12345.678|.3e|1.235e+04
            999999.5|.3e|1.000e+06
            1e-07|.3e|1.000e-07
            1000000.0|,|1,000,000.0
            12345.678|,|12,345.678
            0.25|+.1f|+0.2
            -0.4|08.2f|-0000.40
            2.675|08.2f|00002.67
            0.25|>10|`      0.25`
            256.0|<8g|`256     `
            0.25|^9.1f|`   0.2   `
            999999.5|^9.1f|`999999.5 `
            """)
    void a_float_is_formatted_as_python_formats_it(double value, String spec, String python) {
        assertThat(PythonFormat.format(value, spec)).isEqualTo(python);
    }

    @ParameterizedTest(name = "format({0}, \"{1}\") is \"{2}\"")
    @CsvSource(delimiter = '|', quoteCharacter = '`', ignoreLeadingAndTrailingWhitespace = false, textBlock = """
            1234|d|1234
            1234|,|1,234
            -1234567|,|-1,234,567
            100000|,.0f|100,000
            -1234567|g|-1.23457e+06
            7|.1f|7.0
            1234|>8|`    1234`
            """)
    void an_integer_is_formatted_as_python_formats_it(long value, String spec, String python) {
        assertThat(PythonFormat.format(value, spec)).isEqualTo(python);
    }

    @Test
    void a_spec_it_does_not_know_is_refused_rather_than_guessed() {
        assertThatIllegalArgumentException().isThrownBy(() -> PythonFormat.format(1.5, "b"));
        assertThatIllegalArgumentException().isThrownBy(() -> PythonFormat.format(1.5, "d"));
        assertThatIllegalArgumentException().isThrownBy(() -> PythonFormat.format(1.5, ".2"));
    }

    @Property
    void fixed_point_is_within_half_a_unit_of_the_last_digit(
            @ForAll @DoubleRange(min = -1e9, max = 1e9) double value, @ForAll @IntRange(min = 0, max = 6) int digits) {
        BigDecimal shown = new BigDecimal(PythonFormat.format(value, "." + digits + "f"));
        BigDecimal halfAUnit = BigDecimal.valueOf(5, digits + 1);
        assertThat(shown.subtract(new BigDecimal(value)).abs()).isLessThanOrEqualTo(halfAUnit);
    }

    @Property
    void general_format_keeps_six_significant_digits(@ForAll @DoubleRange(min = 0.01, max = 1e12) double value) {
        String text = PythonFormat.format(value, "g");
        double back = Double.parseDouble(text);
        assertThat(Math.abs(back - value)).isLessThanOrEqualTo(Math.abs(value) * 5e-6);
    }
}
