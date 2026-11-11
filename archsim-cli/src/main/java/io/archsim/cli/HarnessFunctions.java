package io.archsim.cli;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.resources.CpuFits;
import io.archsim.engine.calibration.resources.CurveExtensions;
import io.archsim.engine.calibration.resources.LoadCurve;
import io.archsim.engine.calibration.resources.LoadCurves;
import io.archsim.engine.calibration.resources.MemoryFits;
import io.archsim.engine.calibration.resources.RatePoint;
import io.archsim.engine.host.HostMachine;
import io.archsim.engine.version.EngineVersions;
import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.distribution.Distribution;

/**
 * The engine's functions the benchmark harness calls, by the names of the Python engine's
 * it called before (ADR-0061): each reads its input as JSON and answers as JSON, a fit as
 * its record ({@link RecordCodec}) or {@code null} when it finds none. A request is
 * {@code {"function": NAME, "input": {...}}}; an answer is {@code {"output": VALUE}}, or
 * {@code {"error": {"type", "message"}}} in Python's terms: {@code ValueError} for an
 * input refused or of the wrong shape, {@code LookupError} for a function that is not
 * one. Numbers are read as Python's {@code json} writes them, {@code NaN} and
 * {@code Infinity} included, which the fits leave out as the Python engine's did.
 */
final class HarnessFunctions {

	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
		.build();

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The functions, by name, in name order. */
	private static final Map<String, Function<Input, JsonNode>> FUNCTIONS = functions();

	private HarnessFunctions() {
	}

	private static Map<String, Function<Input, JsonNode>> functions() {
		Map<String, Function<Input, JsonNode>> functions = new TreeMap<>();
		functions.put("constants", HarnessFunctions::constants);
		functions.put("expected", HarnessFunctions::expected);
		functions.put("extend_load_curve", HarnessFunctions::extendLoadCurve);
		functions.put("fit_cpu_share", HarnessFunctions::fitCpuShare);
		functions.put("fit_load_curve", HarnessFunctions::fitLoadCurve);
		functions.put("fit_memory", HarnessFunctions::fitMemory);
		functions.put("load_factor", HarnessFunctions::loadFactor);
		functions.put("machine_speed", HarnessFunctions::machineSpeed);
		functions.put("report_version", HarnessFunctions::reportVersion);
		return Collections.unmodifiableMap(functions);
	}

	/**
	 * The answer to a request.
	 * @param line the request, one line of JSON
	 * @return the answer
	 */
	static ObjectNode answer(String line) {
		JsonNode request;
		try {
			request = JSON.readTree(line);
		}
		catch (JacksonException ex) {
			return error("ValueError", "not JSON: " + ex.getOriginalMessage());
		}
		if (!request.isObject() || !request.path("function").isString()) {
			return error("ValueError", "a request is an object: {\"function\": NAME, \"input\": {...}}");
		}
		String name = request.get("function").stringValue();
		Function<Input, JsonNode> function = FUNCTIONS.get(name);
		if (function == null) {
			return error("LookupError",
					"no function " + name + "; functions: " + String.join(", ", FUNCTIONS.keySet()));
		}
		try {
			return NODES.objectNode().set("output", function.apply(new Input(name, request.path("input"))));
		}
		catch (IllegalArgumentException | BindingException ex) {
			return error("ValueError", String.valueOf(ex.getMessage()));
		}
	}

	private static JsonNode constants(Input input) {
		return NODES.objectNode()
			.put("minCurveChange", LoadCurves.MIN_CHANGE)
			.put("minPerRequestMb", MemoryFits.MIN_PER_REQUEST_MB);
	}

	// the version of the engine's reports, which the harness records with a prediction
	private static JsonNode reportVersion(Input input) {
		return NODES.stringNode(EngineVersions.REPORT);
	}

	private static JsonNode expected(Input input) {
		return NODES.numberNode(RecordCodec.read(input.required("distribution"), Distribution.class).expected());
	}

	// the speed of a busy thread at each busy count, on the machine without other load
	private static JsonNode machineSpeed(Input input) {
		Input machine = input.object("machine");
		HostMachine host = new HostMachine(machine.number("cores"), machine.integer("threadsPerCore"),
				machine.number("siblingSpeed"), machine.number("allCoreClock"), 0.0, 0.0);
		return numbers(input.finiteNumbers("busy").stream().map(host::speed).toList());
	}

	private static JsonNode fitLoadCurve(Input input) {
		return LoadCurves.fit(input.points("points"), input.optionalNumber("referenceMs"), input.integer("maxBins"))
			.map(HarnessFunctions::curve)
			.orElse(NODES.nullNode());
	}

	private static JsonNode extendLoadCurve(Input input) {
		return CurveExtensions.extend(input.points("passes"), input.number("cores"), CurveExtensions.Bounds.DEFAULT)
			.<JsonNode>map(RecordCodec::write)
			.orElse(NODES.nullNode());
	}

	private static JsonNode fitMemory(Input input) {
		List<Double> workingSet = input.numbers("workingSetMb");
		return MemoryFits
			.fit(workingSet, input.optionalNumbers("rate", "workingSetMb", workingSet.size()),
					input.optionalNumbers("held", "workingSetMb", workingSet.size()))
			.<JsonNode>map(RecordCodec::write)
			.orElse(NODES.nullNode());
	}

	private static JsonNode fitCpuShare(Input input) {
		return CpuFits.fitShare(input.points("points"), input.number("serviceMs"), input.number("replicas"))
			.<JsonNode>map((fit) -> RecordCodec.write(fit)
				.set("curve", (fit.curve() != null) ? curve(fit.curve()) : NODES.nullNode()))
			.orElse(NODES.nullNode());
	}

	// a serviceTimeByLoad table's factor at each rate; its unusable points left out
	private static JsonNode loadFactor(Input input) {
		io.archsim.engine.settings.LoadCurve table = io.archsim.engine.settings.LoadCurve.ofJson(input.list("table"));
		return numbers(input.numbers("rps").stream().map(table::factorAt).toList());
	}

	// a curve as its record, with serviceTimeByLoad, the table a calibration sets
	private static JsonNode curve(LoadCurve curve) {
		return RecordCodec.write(curve).set("table", curve.table());
	}

	private static JsonNode numbers(List<Double> values) {
		return NODES.arrayNode().addAll(values.stream().map((value) -> (JsonNode) NODES.numberNode(value)).toList());
	}

	private static ObjectNode error(String type, String message) {
		ObjectNode error = NODES.objectNode().put("type", type).put("message", message);
		return NODES.objectNode().set("error", error);
	}

	/**
	 * A function's input, whose refusals name the function and the field.
	 *
	 * @param function the function's name
	 * @param node the input
	 */
	private record Input(String function, JsonNode node) {

		JsonNode required(String field) {
			JsonNode value = this.node.path(field);
			if (value.isMissingNode() || value.isNull()) {
				throw new IllegalArgumentException(this.function + " needs " + field);
			}
			return value;
		}

		Input object(String field) {
			JsonNode value = required(field);
			if (!value.isObject()) {
				throw refused(field, "an object");
			}
			return new Input(this.function, value);
		}

		JsonNode list(String field) {
			JsonNode value = required(field);
			if (!value.isArray()) {
				throw refused(field, "a list");
			}
			return value;
		}

		// one number, finite: given NaN or Infinity the engine's arithmetic and Python's
		// part ways
		double number(String field) {
			JsonNode value = required(field);
			if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
				throw refused(field, "a finite number");
			}
			return value.doubleValue();
		}

		// a whole number, which Python's json may write as a float (5.0)
		int integer(String field) {
			double value = number(field);
			if (value != Math.rint(value) || Math.abs(value) > Integer.MAX_VALUE) {
				throw refused(field, "a whole number");
			}
			return (int) value;
		}

		// missing or null when the function goes without it
		@Nullable Double optionalNumber(String field) {
			JsonNode value = this.node.path(field);
			return (value.isMissingNode() || value.isNull()) ? null : number(field);
		}

		List<Double> numbers(String field) {
			JsonNode values = required(field);
			if (!values.isArray() || !values.valueStream().allMatch(JsonNode::isNumber)) {
				throw refused(field, "a list of numbers");
			}
			return values.valueStream().map(JsonNode::doubleValue).toList();
		}

		// numbers to evaluate at, not data a fit filters: finite, as for one number
		List<Double> finiteNumbers(String field) {
			List<Double> values = numbers(field);
			if (!values.stream().allMatch(Double::isFinite)) {
				throw refused(field, "a list of finite numbers");
			}
			return values;
		}

		// missing or null when the function goes without it; else as long as another list
		@Nullable List<Double> optionalNumbers(String field, String other, int size) {
			JsonNode value = this.node.path(field);
			if (value.isMissingNode() || value.isNull()) {
				return null;
			}
			List<Double> values = numbers(field);
			if (values.size() != size) {
				throw refused(field, "as long as " + other);
			}
			return values;
		}

		// [[rate, value], ...]
		List<RatePoint> points(String field) {
			JsonNode pairs = required(field);
			if (!pairs.isArray() || !pairs.valueStream().allMatch(Input::isPair)) {
				throw refused(field, "a list of [rate, value] pairs");
			}
			return pairs.valueStream()
				.map((pair) -> new RatePoint(pair.get(0).doubleValue(), pair.get(1).doubleValue()))
				.toList();
		}

		private IllegalArgumentException refused(String field, String shape) {
			return new IllegalArgumentException(this.function + "'s " + field + " is " + shape);
		}

		private static boolean isPair(JsonNode pair) {
			return pair.isArray() && pair.size() == 2 && pair.get(0).isNumber() && pair.get(1).isNumber();
		}

	}

}
