package io.archsim.engine.imports;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.numeric.PythonNumbers;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.text.PythonStrings;

/**
 * CPU and memory as Kubernetes and Compose write them.
 */
final class Quantities {

	/** MiB in a GiB, for the exact divisions of Python's true division. */
	static final BigDecimal MIB_PER_GIB = BigDecimal.valueOf(1024);

	/** Decimals an imported CPU or memory figure is rounded to. */
	private static final int DIGITS = 4;

	private static final double MILLICORES = 1000;

	private static final double KIB = 1 << 10;

	private static final double MIB = 1 << 20;

	private static final double GIB = 1 << 30;

	private static final double TIB = 1L << 40;

	/** What a memory unit multiplies by, as Kubernetes reads it. */
	private static final Map<String, Double> MEMORY_UNITS = Map.of("ki", KIB, "mi", MIB, "gi", GIB, "ti", TIB, "k", 1e3,
			"m", 1e6, "g", 1e9, "t", 1e12, "b", 1.0);

	/**
	 * Docker's units, which are binary in practice ({@code 1536M} is MiB): a unit
	 * starting with one of these letters multiplies by its binary power.
	 */
	private static final Map<Character, Double> BINARY_BY_LETTER = Map.of('k', KIB, 'm', MIB, 'g', GIB, 't', TIB);

	/** The units Docker writes, binary in practice. */
	private static final Pattern DOCKER_UNIT = Pattern.compile("[kmgt]b?");

	/**
	 * A memory figure, once stripped: digits and dots, then the rest. The importer's
	 * {@code re.fullmatch(r"\s*([0-9.]+)\s*([A-Za-z]*)\s*")} reads the rest as whitespace
	 * and a unit, its whitespace what {@code str.isspace()} accepts.
	 */
	private static final Pattern MEMORY = Pattern.compile("([0-9.]+)(.*)", Pattern.DOTALL);

	private static final Pattern UNIT = Pattern.compile("[A-Za-z]*");

	private Quantities() {
	}

	/**
	 * A CPU figure: {@code 2}, {@code "500m"}, {@code "0.5"}.
	 * @param value the value, as written
	 * @return its cores; empty when there is none or it is no number
	 */
	static OptionalDouble cpu(JsonNode value) {
		if (value.isNull() || value.isMissingNode()) {
			return OptionalDouble.empty();
		}
		String text = PythonStrings.strip(PythonValues.str(value));
		try {
			if (text.endsWith("m")) {
				return OptionalDouble.of(PythonRound
					.round(PythonNumbers.parseFloat(text.substring(0, text.length() - 1)) / MILLICORES, DIGITS));
			}
			return OptionalDouble.of(PythonNumbers.parseFloat(text));
		}
		catch (NumberFormatException ex) {
			return OptionalDouble.empty();
		}
	}

	/**
	 * A memory figure in GiB: {@code "512Mi"}, {@code "4Gi"}, {@code "1536M"},
	 * {@code "512m"} (Compose), or plain bytes.
	 * @param value the value, as written
	 * @return its GiB; empty when there is none or its unit is unknown
	 * @throws ImportRefusedException with Python's words for digits that are no number
	 * ({@code 1.2.3})
	 */
	static OptionalDouble memoryGib(JsonNode value) {
		if (value.isNull() || value.isMissingNode()) {
			return OptionalDouble.empty();
		}
		Matcher memory = MEMORY.matcher(PythonStrings.strip(PythonValues.str(value)));
		if (!memory.matches()) {
			return OptionalDouble.empty();
		}
		String unit = PythonStrings.strip(memory.group(2));
		if (!UNIT.matcher(unit).matches()) {
			return OptionalDouble.empty();
		}
		double number;
		try {
			number = PythonNumbers.parseFloat(memory.group(1));
		}
		catch (NumberFormatException ex) {
			throw new ImportRefusedException(String.valueOf(ex.getMessage()), ex);
		}
		Optional<Double> multiplier = factor(unit.toLowerCase(Locale.ROOT));
		return multiplier
			.map((bytesPerUnit) -> OptionalDouble.of(PythonRound.round(number * bytesPerUnit / GIB, DIGITS)))
			.orElseGet(OptionalDouble::empty);
	}

	private static Optional<Double> factor(String unit) {
		if (DOCKER_UNIT.matcher(unit).matches()) {
			return Optional.ofNullable(BINARY_BY_LETTER.get(unit.charAt(0)));
		}
		if (unit.isEmpty() || "b".equals(unit)) {
			return Optional.of(1.0);
		}
		if (MEMORY_UNITS.containsKey(unit)) {
			return Optional.ofNullable(MEMORY_UNITS.get(unit));
		}
		if (unit.endsWith("b") && MEMORY_UNITS.containsKey(unit.substring(0, unit.length() - 1))) {
			return Optional.ofNullable(MEMORY_UNITS.get(unit.substring(0, unit.length() - 1)));
		}
		return Optional.empty();
	}

}
