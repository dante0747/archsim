package io.archsim.engine.random;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.distribution.Distribution;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Python's random draws, as the frozen engine makes them, against the Java generator: the
 * cases of {@code random/cases.json}, which {@code scripts/export_random_cases.py}, since
 * deleted, recorded. Python's names map to Java's: {@code random()} to
 * {@link PythonRandom#nextDouble}, {@code getrandbits} to {@link PythonRandom#nextBits},
 * {@code randrange} to {@link PythonRandom#below}, {@code expovariate} to
 * {@link PythonRandom#exponential}, {@code normalvariate} to {@link PythonRandom#normal}
 * and {@code lognormvariate} to {@link PythonRandom#logNormal}. Draws match bit for bit,
 * except those the file marks as going through {@code log} or {@code exp}.
 */
class RandomCasesTests {

	/**
	 * How far a draw through {@code log} or {@code exp} may be from Python's.
	 * {@code StrictMath} and the C library Python calls round some of them differently in
	 * the last place; a division after the logarithm can double that, and {@code exp}
	 * scales a last-place difference in its argument by the argument (about 13 for a
	 * lognormal at the ten-minute cap): under 1e-14 in all.
	 */
	private static final double LIBM_RELATIVE = 1e-14;

	private static final Comparator<Double> LIBM = RandomCasesTests::closeToLibm;

	private static final JsonNode CASES = read();

	@TestFactory
	Stream<DynamicTest> stream_eachSeedAndName_drawsAsDeriveRng() {
		return cases("streams").map((stream) -> DynamicTest
			.dynamicTest(stream.get("seed").asLong() + " " + stream.get("stream").stringValue(), () -> {
				PythonRandom random = new RandomStreams(stream.get("seed").asLong())
					.stream(stream.get("stream").stringValue());

				List<Double> drawn = draws(random, stream.get("random").size());

				assertThat(drawn).containsExactlyElementsOf(hexes(stream.get("random")));
			}));
	}

	@TestFactory
	Stream<DynamicTest> seeded_eachInteger_givesPythonsWords() {
		return cases("seeds").map((seed) -> DynamicTest.dynamicTest(seed.get("seed").stringValue(), () -> {
			PythonRandom random = PythonRandom.seeded(new BigInteger(seed.get("seed").stringValue()));

			List<Long> words = Stream.generate(() -> random.nextBits(Integer.SIZE))
				.limit(seed.get("words").size())
				.toList();

			assertThat(words)
				.containsExactlyElementsOf(list(seed.get("words")).stream().map(JsonNode::asLong).toList());
		}));
	}

	@TestFactory
	Stream<DynamicTest> seeded_eachInteger_givesPythonsDraws() {
		return cases("seeds").map((seed) -> DynamicTest.dynamicTest(seed.get("seed").stringValue(), () -> {
			PythonRandom random = PythonRandom.seeded(new BigInteger(seed.get("seed").stringValue()));

			List<Double> drawn = draws(random, seed.get("random").size());

			assertThat(drawn).containsExactlyElementsOf(hexes(seed.get("random")));
		}));
	}

	@TestFactory
	Stream<DynamicTest> nextBits_everyWidth_givesPythonsBits() {
		JsonNode bits = CASES.get("bits");
		PythonRandom random = PythonRandom.seeded(BigInteger.valueOf(bits.get("seed").asLong()));
		return each(list(bits.get("getrandbits")), (call) -> random.nextBits(call.get("k").asInt()),
				(call, drawn) -> assertThat(drawn).isEqualTo(call.get("value").asLong()),
				(call) -> "k " + call.get("k"));
	}

	@TestFactory
	Stream<DynamicTest> below_eachBound_givesPythonsValue() {
		JsonNode bits = CASES.get("bits");
		PythonRandom random = PythonRandom.seeded(BigInteger.valueOf(bits.get("rangeSeed").asLong()));
		return each(list(bits.get("randrange")), (call) -> random.below(call.get("n").asInt()),
				(call, drawn) -> assertThat(drawn).isEqualTo(call.get("value").asInt()),
				(call) -> "n " + call.get("n"));
	}

	@TestFactory
	Stream<DynamicTest> uniformAndNormal_inTurnWithTheOtherSamplers_matchBitForBit() {
		return samplerCalls(false, (call, drawn) -> assertThat(drawn).isEqualTo(hex(call.get("value"))));
	}

	@TestFactory
	Stream<DynamicTest> exponentialAndLogNormal_inTurnWithTheOtherSamplers_matchToTheLastPlace() {
		return samplerCalls(true,
				(call, drawn) -> assertThat(drawn).usingComparator(LIBM).isEqualTo(hex(call.get("value"))));
	}

	@TestFactory
	Stream<DynamicTest> sample_distributionsOfArithmetic_matchBitForBit() {
		return distributions(false, (drawn, expected) -> assertThat(drawn).containsExactlyElementsOf(expected));
	}

	@TestFactory
	Stream<DynamicTest> sample_distributionsThroughLogOrExp_matchToTheLastPlace() {
		return distributions(true, (drawn, expected) -> assertThat(drawn).usingElementComparator(LIBM)
			.containsExactlyElementsOf(expected));
	}

	@TestFactory
	Stream<DynamicTest> sample_eachKeyCountAndSkew_drawsPythonsRanks() {
		return cases("zipf").map((zipf) -> DynamicTest.dynamicTest(
				zipf.get("keys").asInt() + " keys, skew " + zipf.get("skew").asDouble(), () -> checkZipf(zipf)));
	}

	private static void checkZipf(JsonNode zipf) {
		ZipfSampler sampler = new ZipfSampler(zipf.get("keys").asInt(), zipf.get("skew").asDouble());
		PythonRandom random = new RandomStreams(7).stream("keys");

		List<Integer> ranks = Stream.generate(() -> sampler.sample(random)).limit(zipf.get("ranks").size()).toList();

		assertThat(ranks).containsExactlyElementsOf(list(zipf.get("ranks")).stream().map(JsonNode::asInt).toList());
	}

	/**
	 * Every sampler call, drawn in turn on one generator (so a sampler that consumes the
	 * wrong number of draws shifts every later value), each checked as a test of its own.
	 * @param throughLibm which calls to check: those through {@code log} or {@code exp},
	 * or the others
	 * @param check how to check one
	 * @return the tests
	 */
	private static Stream<DynamicTest> samplerCalls(boolean throughLibm, Check<Double> check) {
		JsonNode samplers = CASES.get("samplers");
		PythonRandom random = PythonRandom.seeded(BigInteger.valueOf(samplers.get("seed").asLong()));
		List<JsonNode> calls = list(samplers.get("calls"));
		List<Double> drawn = calls.stream().map((call) -> sample(random, call)).toList();
		return IntStream.range(0, calls.size())
			.filter((index) -> calls.get(index).get("throughLibm").asBoolean() == throughLibm)
			.mapToObj((index) -> DynamicTest.dynamicTest(calls.get(index).get("sampler").stringValue() + " #" + index,
					() -> check.check(calls.get(index), drawn.get(index))));
	}

	private static Stream<DynamicTest> distributions(boolean throughLibm, DrawCheck check) {
		return cases("distributions").filter((sampled) -> sampled.get("throughLibm").asBoolean() == throughLibm)
			.map((sampled) -> DynamicTest.dynamicTest(sampled.get("distribution").toString(), () -> {
				Sampler sampler = Sampler.of(RecordCodec.read(sampled.get("distribution"), Distribution.class));
				PythonRandom random = new RandomStreams(7).stream("node:svc:service");

				List<Double> drawn = Stream.generate(() -> sampler.sample(random))
					.limit(sampled.get("samples").size())
					.toList();

				check.check(drawn, hexes(sampled.get("samples")));
			}));
	}

	private static <T> Stream<DynamicTest> each(List<JsonNode> calls, Function<JsonNode, T> draw, Check<T> check,
			Function<JsonNode, String> name) {
		List<T> drawn = calls.stream().map(draw).toList();
		return IntStream.range(0, calls.size())
			.mapToObj((index) -> DynamicTest.dynamicTest(name.apply(calls.get(index)) + " #" + index,
					() -> check.check(calls.get(index), drawn.get(index))));
	}

	private static double sample(PythonRandom random, JsonNode call) {
		List<Double> args = hexes(call.get("args"));
		return switch (call.get("sampler").stringValue()) {
			case "uniform" -> random.uniform(args.get(0), args.get(1));
			case "expovariate" -> random.exponential(args.get(0));
			case "normalvariate" -> random.normal(args.get(0), args.get(1));
			case "lognormvariate" -> random.logNormal(args.get(0), args.get(1));
			default -> throw new IllegalArgumentException(call.get("sampler").stringValue());
		};
	}

	private static int closeToLibm(double actual, double expected) {
		return (Math.abs(actual - expected) <= Math.abs(expected) * LIBM_RELATIVE) ? 0
				: Double.compare(actual, expected);
	}

	private static List<Double> draws(PythonRandom random, int count) {
		return Stream.generate(random::nextDouble).limit(count).toList();
	}

	private static Stream<JsonNode> cases(String name) {
		return list(CASES.get(name)).stream();
	}

	private static List<JsonNode> list(JsonNode array) {
		List<JsonNode> items = new ArrayList<>();
		array.forEach(items::add);
		return items;
	}

	private static List<Double> hexes(JsonNode array) {
		return list(array).stream().map(RandomCasesTests::hex).toList();
	}

	private static double hex(JsonNode value) {
		return Double.parseDouble(value.stringValue());
	}

	private static JsonNode read() {
		try (InputStream cases = Objects.requireNonNull(RandomCasesTests.class.getResourceAsStream("cases.json"),
				"cases.json")) {
			return JsonMapper.builder().build().readTree(cases);
		}
		catch (IOException ex) {
			throw new IllegalStateException("cannot read random/cases.json", ex);
		}
	}

	/**
	 * Checks one recorded call against what Java drew.
	 *
	 * @param <T> what was drawn
	 */
	@FunctionalInterface
	private interface Check<T> {

		void check(JsonNode call, T drawn);

	}

	/**
	 * Checks a distribution's draws against Python's.
	 */
	@FunctionalInterface
	private interface DrawCheck {

		void check(List<Double> drawn, List<Double> expected);

	}

}
