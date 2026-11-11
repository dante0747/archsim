package io.archsim.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.version.EngineVersions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code archsim functions}: the engine's functions the benchmark harness calls, one JSON
 * request a line and one answer a line (ADR-0061). The answers are the frozen Python
 * engine's for the same calls, which the golden files hold the functions to.
 */
class FunctionsCommandTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final double CLOSE = 1e-12;

	private static final String MACHINE = """
			{"cores": 4, "threadsPerCore": 2, "siblingSpeed": 0.5, "allCoreClock": 0.75}""";

	private static final String RISING_POINTS = "[[50, 0.05], [100, 0.095], [200, 0.18], [300, 0.26], [400, 0.33]]";

	@Test
	void functions_expected_isTheDistributionsMean() {
		JsonNode mean = call("expected", """
				{"distribution": {"kind": "lognormal", "median": 8, "p95": 25}}""");

		assertThat(mean.doubleValue()).isCloseTo(10.169334708591919, within(CLOSE));
	}

	@Test
	void functions_machineSpeed_isTheSpeedAtEachBusyCount() {
		JsonNode speeds = call("machine_speed", "{\"machine\": " + MACHINE + ", \"busy\": [1, 3, 6, 8]}");

		assertThat(speeds.valueStream().map(JsonNode::doubleValue).toList()).containsExactly(1.0, 0.9285714285714286,
				0.5476190476190477, 0.375);
	}

	@Test
	void functions_fitMemory_isTheFit() {
		JsonNode fit = call("fit_memory", """
				{"workingSetMb": [300, 320, 345, 370], "rate": [0, 100, 200, 300], "held": null}""");

		assertThat(List.of(fit.get("baselineMb").doubleValue(), fit.get("perRpsMb").doubleValue(),
				fit.get("perRequestMb").doubleValue(), fit.get("error").doubleValue()))
			.containsExactly(298.5, 0.235, 0.01, 0.0027);
		assertThat(fit.get("against").get(0).stringValue()).isEqualTo("rate");
	}

	@Test
	void functions_aPointThatIsNotANumber_isLeftOutOfTheFit() {
		JsonNode fit = call("fit_memory", """
				{"workingSetMb": [300, NaN, 345, 370], "rate": [0, 100, 200, 300]}""");

		assertThat(fit.get("points").intValue()).isEqualTo(3);
		assertThat(List.of(fit.get("baselineMb").doubleValue(), fit.get("perRpsMb").doubleValue()))
			.containsExactly(299.643, 0.232143);
	}

	@Test
	void functions_fitMemoryWithoutAFinitePoint_isNull() {
		JsonNode fit = call("fit_memory", """
				{"workingSetMb": [NaN, Infinity], "rate": [0, 1]}""");

		assertThat(fit.isNull()).isTrue();
	}

	@Test
	void functions_fitLoadCurve_isTheCurve() {
		JsonNode curve = call("fit_load_curve",
				"{\"points\": " + RISING_POINTS + ", \"referenceMs\": null, \"maxBins\": 5}");

		assertThat(curve.get("referenceMs").doubleValue()).isCloseTo(0.8714285714285714, within(CLOSE));
		assertThat(curve.at("/points/0/value").doubleValue()).isCloseTo(1.1475409836065573, within(CLOSE));
		assertThat(curve.at("/points/4/rate").doubleValue()).isEqualTo(400.0);
		assertThat(curve.at("/table/1")).isEqualTo(row(100.0, 1.0902));
	}

	@Test
	void functions_fitLoadCurveToAReference_isTheCurveAgainstIt() {
		JsonNode curve = call("fit_load_curve",
				"{\"points\": " + RISING_POINTS + ", \"referenceMs\": 0.5, \"maxBins\": 5}");

		assertThat(curve.get("referenceMs").doubleValue()).isEqualTo(0.5);
		assertThat(curve.at("/points/0/value").doubleValue()).isCloseTo(2.0, within(CLOSE));
		assertThat(curve.at("/table/3")).isEqualTo(row(300.0, 1.7333));
	}

	@Test
	void functions_aWholeNumberWrittenAsAFloat_isTheWholeNumber() {
		// Python's json writes a float that is whole as 5.0
		JsonNode curve = call("fit_load_curve",
				"{\"points\": " + RISING_POINTS + ", \"referenceMs\": null, \"maxBins\": 5.0}");

		assertThat(curve.at("/points/4/rate").doubleValue()).isEqualTo(400.0);
	}

	@Test
	void functions_fitLoadCurveThatFindsNothing_isNull() {
		JsonNode curve = call("fit_load_curve", """
				{"points": [[50, 0.05]], "referenceMs": null, "maxBins": 1}""");

		assertThat(curve.isNull()).isTrue();
	}

	@Test
	void functions_extendLoadCurve_isTheExtensionWithItsReason() {
		JsonNode extension = call("extend_load_curve", """
				{"passes": [[100, 1.0], [200, 0.92], [300, 0.86], [400, 0.81], [500, 0.77]], "cores": 1}""");

		assertThat(extension.get("reason").stringValue()).isEqualTo("the top rate kept the CPUs 38% busy, under 45%");
		assertThat(extension.get("points").isEmpty()).isTrue();
	}

	@Test
	void functions_extendLoadCurveThatFalls_isTheExtensionToWhereTheCpusFill() {
		JsonNode extension = call("extend_load_curve", """
				{"passes": [[100, 2.0], [200, 1.8], [300, 1.6], [400, 1.45], [500, 1.3]], "cores": 1}""");

		assertThat(extension.get("slope").doubleValue()).isCloseTo(-0.5387345477052042, within(CLOSE));
		assertThat(extension.at("/points/0/rate").doubleValue()).isCloseTo(1220.9668224507088, within(1e-9));
		assertThat(extension.at("/points/0/value").doubleValue()).isCloseTo(0.8190230738561863, within(CLOSE));
	}

	@Test
	void functions_extendLoadCurveWithoutPasses_isNull() {
		JsonNode extension = call("extend_load_curve", """
				{"passes": [], "cores": 1}""");

		assertThat(extension.isNull()).isTrue();
	}

	@Test
	void functions_fitCpuShare_isTheShareWithItsCurve() {
		JsonNode fit = call("fit_cpu_share", """
				{"points": [[100, 0.08], [200, 0.16], [300, 0.25]], "serviceMs": 1.0, "replicas": 1.0}""");

		assertThat(fit.get("share").doubleValue()).isEqualTo(0.8167);
		assertThat(fit.at("/curve/referenceMs").doubleValue()).isCloseTo(0.8166666666666667, within(CLOSE));
		assertThat(fit.at("/curve/table/2")).isEqualTo(row(300.0, 1.0));
	}

	@Test
	void functions_aShareWhoseCurveFindsNothing_hasNoCurve() {
		JsonNode fit = call("fit_cpu_share", """
				{"points": [[100, 0.08], [101, 0.08]], "serviceMs": 1.0, "replicas": 1.0}""");

		assertThat(fit.get("share").doubleValue()).isEqualTo(0.796);
		assertThat(fit.get("curve").isNull()).isTrue();
	}

	@Test
	void functions_fitCpuShareWithoutPoints_isNull() {
		JsonNode fit = call("fit_cpu_share", """
				{"points": [], "serviceMs": 1.0, "replicas": 1.0}""");

		assertThat(fit.isNull()).isTrue();
	}

	@Test
	void functions_loadFactor_isTheTablesFactorAtEachRate() {
		JsonNode factors = call("load_factor", """
				{"table": [{"rps": 50, "factor": 2.0}, {"rps": 100, "factor": 1.9}, {"rps": 400, "factor": 1.65}],
				 "rps": [10, 75, 200, 1000]}""");

		assertThat(factors.valueStream().map(JsonNode::doubleValue).toList()).containsExactly(2.0, 1.9415037499278844,
				1.775, 1.65);
	}

	@Test
	void functions_loadFactorOfAnEmptyTable_isOne() {
		JsonNode factors = call("load_factor", """
				{"table": [], "rps": [100]}""");

		assertThat(factors.valueStream().map(JsonNode::doubleValue).toList()).containsExactly(1.0);
	}

	@Test
	void functions_loadFactorAtARateThatIsNotANumber_isTheLastFactorAsInPython() {
		JsonNode factors = call("load_factor", """
				{"table": [{"rps": 50, "factor": 2.0}, {"rps": 400, "factor": 1.5}], "rps": [NaN]}""");

		assertThat(factors.valueStream().map(JsonNode::doubleValue).toList()).containsExactly(1.5);
	}

	@Test
	void functions_loadFactorOfATablesUnusablePoints_skipsThemAsPythonsCurveOfDid() {
		JsonNode factors = call("load_factor", """
				{"table": [{"rps": 0, "factor": 2.0}, {"rps": 100, "factor": true}, {"rps": 50}, [1, 2],
				 {"rps": 200, "factor": 1.5}], "rps": [100, 400]}""");

		assertThat(factors.valueStream().map(JsonNode::doubleValue).toList()).containsExactly(1.5, 1.5);
	}

	@Test
	void functions_reportVersion_isTheEnginesReports() {
		JsonNode version = call("report_version", "{}");

		assertThat(version.stringValue()).isEqualTo(EngineVersions.REPORT);
	}

	@Test
	void functions_constants_areTheEngines() {
		JsonNode constants = call("constants", "{}");

		assertThat(constants)
			.isEqualTo(JSON.createObjectNode().put("minCurveChange", 0.1).put("minPerRequestMb", 0.01));
	}

	@Test
	void functions_anAnswer_isWrittenAsPythonsJsonWritesIt() {
		StringWriter out = new StringWriter();
		String request = request("expected", "{\"distribution\": {\"kind\": \"constant\", \"value\": 0.00001}}");

		Archsim
			.commandLine(new ByteArrayInputStream((request + "\n").getBytes(StandardCharsets.UTF_8)), Clock.systemUTC())
			.setOut(new PrintWriter(out))
			.execute("functions");

		// json.dumps(answer, separators=(",", ":")): a float as its repr
		assertThat(out).hasToString("{\"output\":1e-05}\n");
	}

	@Test
	void functions_aFitRefused_isAnErrorInPythonsWords() {
		JsonNode answer = answers(request("fit_memory", """
				{"workingSetMb": [300, 310], "rate": null, "held": null}""")).getFirst();

		assertThat(answer.get("error")).isEqualTo(JSON.createObjectNode()
			.put("type", "ValueError")
			.put("message", "fit the working set against the request rate, requests held, or both"));
	}

	@Test
	void functions_anUnknownFunction_isAnErrorNamingTheFunctions() {
		JsonNode answer = answers(request("nowhere", "{}")).getFirst();

		assertThat(answer.get("error")).isEqualTo(JSON.createObjectNode()
			.put("type", "LookupError")
			.put("message", "no function nowhere; functions: constants, expected, extend_load_curve, fit_cpu_share, "
					+ "fit_load_curve, fit_memory, load_factor, machine_speed, report_version"));
	}

	@Test
	void functions_aLineThatIsNotARequest_isAnErrorAndTheNextIsAnswered() {
		List<JsonNode> answers = answers("{not json", "[1]", request("constants", "{}"));

		assertThat(answers).hasSize(3);
		assertThat(answers.get(0).at("/error/type").stringValue()).isEqualTo("ValueError");
		assertThat(answers.get(1).at("/error/message").stringValue())
			.isEqualTo("a request is an object: {\"function\": NAME, \"input\": {...}}");
		assertThat(answers.get(2).at("/output/minCurveChange").doubleValue()).isEqualTo(0.1);
	}

	@Test
	void functions_anInputWithoutWhatTheFunctionNeeds_isAnError() {
		JsonNode answer = answers(request("machine_speed", "{\"busy\": [1]}")).getFirst();

		assertThat(answer.get("error"))
			.isEqualTo(JSON.createObjectNode().put("type", "ValueError").put("message", "machine_speed needs machine"));
	}

	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("inputsOfTheWrongShape")
	void functions_anInputOfTheWrongShape_isAnErrorNamingTheShape(String function, String input, String message) {
		JsonNode answer = answers(request(function, input)).getFirst();

		assertThat(answer.get("error"))
			.isEqualTo(JSON.createObjectNode().put("type", "ValueError").put("message", message));
	}

	// a function, an input of the wrong shape, and the refusal's message
	static Stream<Arguments> inputsOfTheWrongShape() {
		return Stream.of(
				Arguments.of("machine_speed", "{\"machine\": " + MACHINE + ", \"busy\": [\"one\"]}",
						"machine_speed's busy is a list of numbers"),
				Arguments.of("machine_speed", "{\"machine\": " + MACHINE + ", \"busy\": [2, NaN]}",
						"machine_speed's busy is a list of finite numbers"),
				Arguments.of("machine_speed",
						"{\"machine\": {\"cores\": 4, \"threadsPerCore\": 2.5, \"siblingSpeed\": 0.5, "
								+ "\"allCoreClock\": 0.75}, \"busy\": [1]}",
						"machine_speed's threadsPerCore is a whole number"),
				Arguments.of("fit_load_curve", "{\"points\": [[50]], \"maxBins\": 1}",
						"fit_load_curve's points is a list of [rate, value] pairs"),
				Arguments.of("fit_load_curve", "{\"points\": [[50, 0.05]], \"referenceMs\": \"abc\", \"maxBins\": 1}",
						"fit_load_curve's referenceMs is a finite number"),
				Arguments.of("fit_load_curve", "{\"points\": [[50, 0.05]], \"maxBins\": Infinity}",
						"fit_load_curve's maxBins is a finite number"),
				Arguments.of("fit_cpu_share", "{\"points\": [[100, 0.08]], \"serviceMs\": NaN, \"replicas\": 1}",
						"fit_cpu_share's serviceMs is a finite number"),
				Arguments.of("fit_memory", "{\"workingSetMb\": {\"a\": 300}, \"rate\": [0]}",
						"fit_memory's workingSetMb is a list of numbers"),
				Arguments.of("fit_memory", "{\"workingSetMb\": [300], \"rate\": 100}",
						"fit_memory's rate is a list of numbers"),
				Arguments.of("fit_memory", "{\"workingSetMb\": [300, 320, 345], \"rate\": [0]}",
						"fit_memory's rate is as long as workingSetMb"),
				Arguments.of("load_factor", "{\"table\": {\"rps\": 50, \"factor\": 2.0}, \"rps\": [1]}",
						"load_factor's table is a list"));
	}

	@Test
	void functions_eachAnswer_isWrittenBeforeTheNextLineIsRead() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		List<String> writtenAtEachRead = new ArrayList<>();
		InputStream input = new InputStream() {

			private final ByteArrayInputStream request = new ByteArrayInputStream(
					(request("constants", "{}") + "\n").getBytes(StandardCharsets.UTF_8));

			@Override
			public int read() {
				writtenAtEachRead.add(out.toString(StandardCharsets.UTF_8));
				return this.request.read();
			}

			@Override
			public int read(byte[] buffer, int offset, int length) {
				writtenAtEachRead.add(out.toString(StandardCharsets.UTF_8));
				return this.request.read(buffer, offset, length);
			}

		};

		int exitCode = Archsim.commandLine(input, Clock.systemUTC())
			.setOut(new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8)))
			.setErr(new PrintWriter(new StringWriter()))
			.execute("functions");

		assertThat(exitCode).isZero();
		assertThat(writtenAtEachRead.getLast()).contains("\"output\"");
	}

	// a {rps, factor} row of a curve's serviceTimeByLoad table
	private static ObjectNode row(double rps, double factor) {
		return JSON.createObjectNode().put("rps", rps).put("factor", factor);
	}

	// the output of one call, failing the test on an error
	private static JsonNode call(String function, String input) {
		JsonNode answer = answers(request(function, input)).getFirst();
		assertThat(answer.has("error")).as("%s", answer).isFalse();
		return answer.get("output");
	}

	// a request line: the input may be written over lines, a request is one
	private static String request(String function, String input) {
		return "{\"function\": \"" + function + "\", \"input\": " + input.strip().replace('\n', ' ') + "}";
	}

	// the command's answers, one a line, to the requests sent before the input ends
	private static List<JsonNode> answers(String... requests) {
		StringWriter out = new StringWriter();
		byte[] input = String.join("\n", requests).concat("\n").getBytes(StandardCharsets.UTF_8);
		int exitCode = Archsim.commandLine(new ByteArrayInputStream(input), Clock.systemUTC())
			.setOut(new PrintWriter(out))
			.setErr(new PrintWriter(new StringWriter()))
			.execute("functions");
		assertThat(exitCode).isZero();
		return out.toString().lines().map(JSON::readTree).toList();
	}

}
