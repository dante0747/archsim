package io.archsim.engine.numeric;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The complementary error function against Python 3.12's {@code math.erfc}, at the points
 * of {@code erfc.csv}: every interval of the approximation and its edges.
 */
class ErfTests {

	/** Python's {@code erfc} and fdlibm's may differ in the last place. */
	private static final double RELATIVE = 1e-15;

	@TestFactory
	Stream<DynamicTest> erfc_eachPoint_matchesPython() throws IOException {
		return points().stream()
			.map((point) -> DynamicTest.dynamicTest(Double.toString(point[0]),
					() -> assertThat(Erf.erfc(point[0])).isCloseTo(point[1], within(Math.abs(point[1]) * RELATIVE))));
	}

	@Test
	void erfc_notANumber_isNotANumber() {
		assertThat(Erf.erfc(Double.NaN)).isNaN();
	}

	@Test
	void erfc_infinity_isZero() {
		assertThat(Erf.erfc(Double.POSITIVE_INFINITY)).isZero();
	}

	@Test
	void erfc_negativeInfinity_isTwo() {
		assertThat(Erf.erfc(Double.NEGATIVE_INFINITY)).isEqualTo(2.0);
	}

	private static List<double[]> points() throws IOException {
		try (InputStream in = Objects.requireNonNull(ErfTests.class.getResourceAsStream("erfc.csv"), "erfc.csv");
				BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			return lines.lines()
				.filter((line) -> !line.startsWith("#"))
				.map((line) -> line.split(","))
				.map((parts) -> new double[] { Double.parseDouble(parts[0]), Double.parseDouble(parts[1]) })
				.toList();
		}
	}

}
