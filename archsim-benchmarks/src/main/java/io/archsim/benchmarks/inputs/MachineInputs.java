package io.archsim.benchmarks.inputs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.ContainerNodes;
import io.archsim.benchmarks.machine.MachineFit;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * The design's host machine ({@code architecture.host}) from the machine's contention fit
 * and the top pass's own load on it, and the CPU-time inputs at full speed (W27).
 *
 * <p>
 * At the top pass the machine had {@code D} busy threads: every container's CPU over the
 * pass, k6's (the rate times its CPU per request) and the background at rest. The pass's
 * CPU per request was measured at {@code speed(D)}, so the model's inputs are that times
 * {@code speed(D)} ({@link #SCALED}, {@code dbCpuShare}). Containers the model gives no
 * CPU (Kafka's broker and consumer) join the host's other load: their CPU at rest as busy
 * threads, the rest per request with k6's.
 */
final class MachineInputs {

	/**
	 * CPU-time inputs measured at a calibration pass, which ran at the machine's thread
	 * speed then: the model's are at full speed (the database's is its CPU share,
	 * {@code dbCpuShare}).
	 */
	static final List<String> SCALED = List.of("serviceTimeMs", "apiServiceTimeMs", "cacheServiceTimeMs");

	/** A distribution's times. */
	private static final List<String> TIMES = List.of("median", "p95", "mean", "value", "min", "max");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final double MS_PER_S = 1000.0;

	private static final int TIME_DECIMALS = 6;

	private static final int DECIMALS = 4;

	private MachineInputs() {
	}

	/**
	 * Gives the inputs their host machine, and takes their CPU-time inputs to full speed.
	 * @param passes the calibration's passes: the top pass with each container's CPU, and
	 * the idle pass
	 * @param inputs the inputs, changed in place
	 * @param fit the machine's fit
	 */
	static void apply(JsonNode passes, ObjectNode inputs, MachineFit fit) {
		JsonNode high = passes.get("high");
		Optional<PassLoad> load = PassLoad.of(high, fit);
		if (load.isEmpty()) {
			return;
		}
		PassLoad pass = load.get();
		String at = "x " + format(pass.speed(), ".4f") + ", the machine's thread speed at the pass ("
				+ format(pass.busy(), ".2f") + " busy threads: the containers " + format(pass.containers(), ".2f")
				+ ", k6 " + format(pass.rate() * fit.k6CpuMsPerRequest() / MS_PER_S, ".2f") + ", at rest "
				+ format(fit.backgroundThreads(), ".2f") + ")";
		for (String key : SCALED) {
			if (inputs.has(key) && inputs.get(key).has("value")) {
				scale(inputs, key, scaled(inputs.get(key).get("value"), pass.speed()), at);
			}
		}
		if (inputs.has("dbCpuShare")) {
			double share = Math.min(1.0, inputs.at("/dbCpuShare/value").doubleValue() * pass.speed());
			scale(inputs, "dbCpuShare", NODES.numberNode(PythonRound.round(share, DECIMALS)), at);
		}
		inputs.set("host", host(passes, pass, fit));
	}

	/**
	 * A time input, a number or a distribution, times a factor.
	 * @param value the input's value
	 * @param factor the factor
	 * @return the value scaled; anything else as it was
	 */
	static JsonNode scaled(JsonNode value, double factor) {
		if (value.isNumber()) {
			return NODES.numberNode(PythonRound.round(value.doubleValue() * factor, TIME_DECIMALS));
		}
		if (!value.isObject()) {
			return value;
		}
		ObjectNode out = ((ObjectNode) value).deepCopy();
		TIMES.stream()
			.filter((key) -> out.path(key).isNumber())
			.forEach((key) -> out.put(key, PythonRound.round(out.get(key).doubleValue() * factor, TIME_DECIMALS)));
		return out;
	}

	// an input with its new value, and the speed it was taken to in its how
	private static void scale(ObjectNode inputs, String key, JsonNode value, String at) {
		ObjectNode input = ((ObjectNode) inputs.get(key)).deepCopy();
		input.set("value", value);
		input.put("how", PythonText.str(input.path("how")) + "; " + at);
		inputs.set(key, input);
	}

	// the machine with the other load on it: the background, k6, and the containers the
	// model gives no CPU
	private static ObjectNode host(JsonNode passes, PassLoad pass, MachineFit fit) {
		JsonNode idle = passes.path("idle").path("containerCpuMsPerSecond");
		List<String> others = pass.unmodelled();
		List<Double> idleMs = others.stream().map((container) -> idle.path(container).asDouble(0.0)).toList();
		// added as CPython 3.12's sum() adds floats
		double atRest = PythonValues.sumOfFloats(idleMs.stream().map((ms) -> ms / MS_PER_S).toList());
		double atRestMs = PythonValues.sumOfFloats(idleMs);
		double perRequest = PythonValues.sumOfFloats(IntStream.range(0, others.size())
			.mapToObj((index) -> Math.max(0.0,
					pass.cores().getOrDefault(others.get(index), 0.0) - idleMs.get(index) / MS_PER_S) * MS_PER_S
					/ pass.rate())
			.toList());
		ObjectNode host = NODES.objectNode();
		host.putObject("value")
			.put("physicalCores", fit.physicalCores())
			.put("threadsPerCore", fit.threadsPerCore())
			.put("siblingSpeed", fit.siblingSpeed())
			.put("allCoreClock", fit.allCoreClock())
			.put("otherBusyThreads", PythonRound.round(fit.backgroundThreads() + atRest, DECIMALS))
			.put("clientCpuMs", PythonRound.round(fit.k6CpuMsPerRequest() + perRequest, DECIMALS));
		String unmodelled = others.isEmpty() ? ""
				: " and " + String.join(", ", others) + " (no CPU in the model: " + format(atRestMs / MS_PER_S, ".2f")
						+ " cores at rest, " + format(perRequest, ".4f") + " ms per request)";
		host.put("how", fit.how() + "; other load: the background, k6" + unmodelled);
		return host;
	}

	private static String format(double value, String spec) {
		return PythonFormat.format(value, spec);
	}

	/**
	 * The machine during the top pass.
	 *
	 * @param cores each container's busy cores over the pass, in the pass's order
	 * @param rate the pass's rate, in requests per second
	 * @param busy the machine's busy threads: the containers, k6 and the background
	 * @param speed a busy thread's speed then
	 */
	private record PassLoad(Map<String, Double> cores, double rate, double busy, double speed) {

		/**
		 * The machine's load during a pass.
		 * @param entry the pass
		 * @param fit the machine's fit
		 * @return the load; empty without the pass's CPU readings
		 */
		static Optional<PassLoad> of(JsonNode entry, MachineFit fit) {
			double wallS = entry.path("service").path("wallMs").asDouble(0.0) / MS_PER_S;
			double rate = entry.path("rateRps").asDouble(0.0);
			JsonNode used = entry.path("containerCpuMs");
			if (wallS <= 0 || rate <= 0 || !PythonText.truthy(used)) {
				return Optional.empty();
			}
			Map<String, Double> cores = new LinkedHashMap<>();
			used.properties()
				.stream()
				.filter((container) -> container.getValue().isNumber())
				.forEach((container) -> cores.put(container.getKey(),
						container.getValue().doubleValue() / MS_PER_S / wallS));
			double busy = containers(cores) + rate * fit.k6CpuMsPerRequest() / MS_PER_S + fit.backgroundThreads();
			return Optional.of(new PassLoad(cores, rate, busy, fit.speed(busy)));
		}

		// added as CPython 3.12's sum() adds floats
		private static double containers(Map<String, Double> cores) {
			return PythonValues.sumOfFloats(List.copyOf(cores.values()));
		}

		double containers() {
			return containers(this.cores);
		}

		// the containers the model gives no CPU (Kafka's broker and consumer),
		// in the pass's order
		List<String> unmodelled() {
			return this.cores.keySet().stream().filter((container) -> ContainerNodes.of(container).isEmpty()).toList();
		}

	}

}
