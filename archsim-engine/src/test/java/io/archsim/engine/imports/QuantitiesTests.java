package io.archsim.engine.imports;

import java.util.HexFormat;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * CPU and memory as Kubernetes and Compose write them.
 */
class QuantitiesTests {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	@Test
	void cpu_millicores_areCores() {
		assertThat(Quantities.cpu(NODES.stringNode("500m"))).hasValue(0.5);
	}

	@Test
	void cpu_textThatIsNoNumber_isNone() {
		assertThat(Quantities.cpu(NODES.stringNode("lots"))).isEmpty();
	}

	/**
	 * The importer strips a CPU figure with Python's {@code str.strip()}.
	 */
	@ParameterizedTest(name = "500m and U+{0} is half a core", quoteTextArguments = false)
	@ValueSource(strings = { "00A0", "2007", "202F", "0085", "001C" })
	void cpu_millicoresFollowedBySpace_areCores(String hex) {
		String text = "500m" + Character.toString(HexFormat.fromHexDigits(hex));

		assertThat(Quantities.cpu(NODES.stringNode(text))).hasValue(0.5);
	}

	/**
	 * The importer matches a memory figure with Python's {@code re}, whose whitespace is
	 * what {@code str.isspace()} accepts: the information separators included.
	 */
	@ParameterizedTest(name = "512Mi with {0} is half a GiB", quoteTextArguments = false)
	@MethodSource("spacedMemory")
	void memoryGib_spaceAroundOrInside_isWhitespaceAsInPython(String where, String text) {
		assertThat(Quantities.memoryGib(NODES.stringNode(text))).as(where).hasValue(0.5);
	}

	static Stream<Arguments> spacedMemory() {
		String fileSeparator = Character.toString(0x1C);
		String unitSeparator = Character.toString(0x1F);
		String noBreakSpace = Character.toString(0xA0);
		String ideographicSpace = Character.toString(0x3000);
		return Stream.of(Arguments.of("a unit separator after it", "512Mi" + unitSeparator),
				Arguments.of("a file separator before it", fileSeparator + "512 Mi"),
				Arguments.of("a unit separator inside it", "512" + unitSeparator + "Mi"),
				Arguments.of("a no-break space after it", "512Mi" + noBreakSpace),
				Arguments.of("an ideographic space before it", ideographicSpace + "512 Mi"));
	}

	@Test
	void memoryGib_textAfterTheUnit_isNone() {
		assertThat(Quantities.memoryGib(NODES.stringNode("512Mi x"))).isEmpty();
	}

	/**
	 * Python's pattern fails before {@code float()} reads the digits, so nothing is
	 * refused.
	 */
	@Test
	void memoryGib_digitsThatAreNoNumberBeforeTextThatIsNoUnit_isNone() {
		assertThat(Quantities.memoryGib(NODES.stringNode("1.2.3 x!"))).isEmpty();
	}

	@Test
	void memoryGib_dockersMegabytes_areBinary() {
		assertThat(Quantities.memoryGib(NODES.stringNode("1536M"))).hasValue(1.5);
	}

	@Test
	void memoryGib_kubernetesGibibytes_areGib() {
		assertThat(Quantities.memoryGib(NODES.stringNode("4Gi"))).hasValue(4.0);
	}

	@Test
	void memoryGib_unknownUnit_isNone() {
		assertThat(Quantities.memoryGib(NODES.stringNode("4 parsecs"))).isEmpty();
	}

	@Test
	void memoryGib_digitsThatAreNoNumber_areRefusedInPythonsWords() {
		assertThatExceptionOfType(ImportRefusedException.class)
			.isThrownBy(() -> Quantities.memoryGib(NODES.stringNode("1.2.3Gi")))
			.withMessage("could not convert string to float: '1.2.3'");
	}

}
