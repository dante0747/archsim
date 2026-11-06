package io.archsim.engine.imports;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.engine.numeric.PythonRound;

/**
 * The vCPUs and memory of a cloud's instance types, from the size names the clouds
 * document: an AWS instance class ({@code db.r6g.large}), a GCP machine type
 * ({@code e2-standard-4}, {@code db-custom-2-7680}) and an Azure size
 * ({@code Standard_D4s_v5}). A name the tables do not hold has no size, and is sized by
 * hand.
 */
final class InstanceSizes {

	/** AWS sizes (the part after the dot), by vCPU. */
	private static final Map<String, Integer> AWS_SIZE_VCPU = Map.of("medium", 1, "large", 2, "xlarge", 4, "2xlarge", 8,
			"4xlarge", 16, "8xlarge", 32, "12xlarge", 48, "16xlarge", 64, "24xlarge", 96);

	/** GiB per vCPU by AWS family letter: general purpose, compute, memory and more. */
	private static final Map<String, Integer> AWS_GIB_PER_VCPU = Map.of("m", 4, "c", 2, "r", 8, "x", 16, "z", 8);

	/** AWS burstable (t) sizes: vCPU and GiB. */
	private static final Map<String, Size> AWS_BURSTABLE = Map.of("micro", Size.of(2, 1), "small", Size.of(2, 2),
			"medium", Size.of(2, 4), "large", Size.of(2, 8), "xlarge", Size.of(4, 16), "2xlarge", Size.of(8, 32));

	private static final Pattern GCP_CUSTOM = digits("db-custom-(?<cpu>\\d+)-(?<memory>\\d+)");

	private static final Pattern GCP_SERIES = digits(
			"(?:db-)?[a-z0-9]+-(?<series>standard|highmem|highcpu)-(?<cpu>\\d+)");

	/** GiB per vCPU by GCP series. */
	private static final Map<String, Integer> GCP_GIB_PER_VCPU = Map.of("standard", 4, "highmem", 8, "highcpu", 1);

	/** GiB per vCPU by series of Cloud SQL's first generation ({@code db-n1-}). */
	private static final Map<String, Double> GCP_N1_GIB_PER_VCPU = Map.of("standard", 3.75, "highmem", 6.5, "highcpu",
			0.9);

	private static final Pattern AZURE = Pattern.compile(
			"Standard_(?<series>[DEF])(?<cpu>\\d+)(?<features>[a-z]*)_v(?<version>\\d+)$",
			Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);

	/** The versions of each Azure series whose memory per vCPU is fixed. */
	private static final Map<String, Set<String>> AZURE_VERSIONS = Map.of("D", Set.of("3", "4", "5", "6"), "E",
			Set.of("3", "4", "5", "6"), "F", Set.of("2"));

	/** GiB per vCPU by Azure series. */
	private static final Map<String, Integer> AZURE_GIB_PER_VCPU = Map.of("D", 4, "E", 8, "F", 2);

	/** The decimals a GCP size's memory is rounded to. */
	private static final int MEMORY_DECIMALS = 2;

	private InstanceSizes() {
	}

	/**
	 * The size of an AWS instance class: {@code db.r6g.large}, {@code cache.m7g.xlarge},
	 * {@code kafka.m5.large}.
	 * @param instanceClass the class, as written
	 * @return its vCPUs and GiB; empty for none, or one the tables do not hold
	 * @throws ImportShapeException in Python's words for a class that is not text
	 */
	static Optional<Size> aws(JsonNode instanceClass) {
		if (!PythonValues.truthy(instanceClass)) {
			return Optional.empty();
		}
		if (!instanceClass.isString()) {
			throw new ImportShapeException(
					"'" + PythonValues.type(instanceClass) + "' object has no attribute 'split'");
		}
		String[] parts = instanceClass.stringValue().split("\\.", -1);
		if (parts.length < 2) {
			return Optional.empty();
		}
		String family = parts[parts.length - 2];
		String size = parts[parts.length - 1];
		if (family.startsWith("t")) {
			return Optional.ofNullable(AWS_BURSTABLE.get(size));
		}
		Integer vcpu = AWS_SIZE_VCPU.get(size);
		Integer perVcpu = AWS_GIB_PER_VCPU
			.get(family.isEmpty() ? "" : family.substring(0, family.offsetByCodePoints(0, 1)));
		return (vcpu != null && perVcpu != null) ? Optional.of(Size.of(vcpu, vcpu * perVcpu)) : Optional.empty();
	}

	/**
	 * The size of a GCP machine type: {@code e2-standard-4}, {@code n2-highmem-8},
	 * {@code db-custom-2-7680}, {@code db-n1-standard-2}.
	 * @param machine the machine type, as written
	 * @return its vCPUs and GiB; empty for none, or one the tables do not hold
	 * @throws ImportShapeException in Python's words for a type that is not text
	 * @throws ImportRefusedException in Python's words for a size beyond a float's range,
	 * or of more digits than it converts
	 */
	static Optional<Size> gcp(JsonNode machine) {
		if (!PythonValues.truthy(machine)) {
			return Optional.empty();
		}
		String text = PythonValues.matchedText(machine);
		Matcher custom = GCP_CUSTOM.matcher(text);
		if (custom.matches()) {
			double memory = PythonValues.divided(whole(custom.group("memory")), Quantities.MIB_PER_GIB);
			return Optional
				.of(new Size(whole(custom.group("cpu")), number(PythonRound.round(memory, MEMORY_DECIMALS))));
		}
		Matcher series = GCP_SERIES.matcher(text);
		if (!series.matches()) {
			return Optional.empty();
		}
		BigInteger cpu = PythonValues.toInt(text(series.group("cpu")));
		JsonNode memory;
		if (text.startsWith("db-n1")) {
			double perVcpu = Objects.requireNonNull(GCP_N1_GIB_PER_VCPU.get(series.group("series")));
			memory = number(
					PythonRound.round(PythonValues.toFloat(PythonValues.number(cpu)) * perVcpu, MEMORY_DECIMALS));
		}
		else {
			memory = PythonValues.number(cpu
				.multiply(BigInteger.valueOf(Objects.requireNonNull(GCP_GIB_PER_VCPU.get(series.group("series"))))));
		}
		return Optional.of(new Size(PythonValues.number(cpu), memory));
	}

	/**
	 * The size of an Azure size: {@code Standard_D4s_v5}, {@code GP_Standard_D2ds_v4},
	 * {@code Standard_E8s_v5}, {@code Standard_F4s_v2}, of the series whose memory per
	 * vCPU is fixed (D and E v3 to v6, F v2). Older series, and low-memory ({@code l})
	 * sizes, have none.
	 * @param sku the size, as written
	 * @return its vCPUs and GiB; empty for none, or one the tables do not hold
	 * @throws ImportShapeException in Python's words for a size that is not text
	 * @throws ImportRefusedException with Python's words for more digits than it converts
	 */
	static Optional<Size> azure(JsonNode sku) {
		if (!PythonValues.truthy(sku)) {
			return Optional.empty();
		}
		Matcher size = AZURE.matcher(PythonValues.matchedText(sku));
		if (!size.find()
				|| !Objects.requireNonNull(AZURE_VERSIONS.get(size.group("series"))).contains(size.group("version"))
				|| size.group("features").contains("l")) {
			return Optional.empty();
		}
		BigInteger cpu = PythonValues.toInt(text(size.group("cpu")));
		BigInteger memory = cpu
			.multiply(BigInteger.valueOf(Objects.requireNonNull(AZURE_GIB_PER_VCPU.get(size.group("series")))));
		return Optional.of(new Size(PythonValues.number(cpu), PythonValues.number(memory)));
	}

	/**
	 * {@code int(digits)}.
	 * @param digits the digits
	 * @return the whole number
	 * @throws ImportRefusedException with Python's words for more digits than it converts
	 */
	private static JsonNode whole(String digits) {
		return PythonValues.number(PythonValues.toInt(text(digits)));
	}

	private static JsonNode text(String digits) {
		return JsonNodeFactory.instance.stringNode(digits);
	}

	private static JsonNode number(double value) {
		return JsonNodeFactory.instance.numberNode(value);
	}

	/**
	 * A pattern whose {@code \d} matches any script's digits, as Python's does.
	 * @param pattern the pattern
	 * @return the pattern, compiled
	 */
	private static Pattern digits(String pattern) {
		return Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS);
	}

	/**
	 * An instance's vCPUs and memory, each a whole number or a float as Python computed
	 * it.
	 *
	 * @param cpu the vCPUs
	 * @param memory the GiB
	 */
	record Size(JsonNode cpu, JsonNode memory) {

		static Size of(int cpu, int memory) {
			return new Size(JsonNodeFactory.instance.numberNode(cpu), JsonNodeFactory.instance.numberNode(memory));
		}

	}

}
