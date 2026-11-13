package io.archsim.benchmarks.inputs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;

/**
 * Redis's inputs: the time a command keeps its one thread busy, and the service-to-Redis
 * latency. The command time is Redis's CPU per command: INFO commandstats' own time
 * leaves out reading the request and writing the reply, about nine tenths of Redis's CPU.
 * Without a CPU figure it is commandstats' own time.
 */
final class CacheInputs {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final double US_PER_MS = 1000;

	private static final double LEAST_MEAN = 0.001;

	private static final int MEAN_DECIMALS = 5;

	private static final int DECIMALS = 4;

	private CacheInputs() {
	}

	/**
	 * Redis's inputs from the top pass.
	 * @param experiment the experiment
	 * @param passes the calibration's passes
	 * @return the inputs
	 */
	static ObjectNode of(Experiment experiment, JsonNode passes) {
		JsonNode high = passes.get("high");
		Commands commands = Commands.of(high);
		double ownMs = commands.usec() / Math.max(commands.calls(), 1) / US_PER_MS;
		JsonNode get = PassReadings.timing(high.get("service"), "redis_get");
		Optional<CpuPerCommand> cpu = cpuPerCommand(experiment, passes);
		String own = "INFO commandstats: " + PythonFormat.format(commands.usec(), ".0f") + " us over "
				+ PythonFormat.format(commands.calls(), ".0f") + " GET/SET calls = " + PythonFormat.format(ownMs, ".4f")
				+ " ms";
		double mean = cpu.map(CpuPerCommand::ms).orElse(ownMs);
		String how = cpu
			.map((found) -> "mean = " + found.how() + " (its command time alone, " + own
					+ "; the rest is reading requests and writing replies)")
			.orElse("Redis " + own + " (no CPU figure for its container)");
		mean = PythonRound.round(Math.max(mean, LEAST_MEAN), MEAN_DECIMALS);
		double commandMedian = mean * StrictMath.log(2);
		ObjectNode inputs = NODES.objectNode();
		ObjectNode serviceTime = inputs.putObject("cacheServiceTimeMs");
		serviceTime.putObject("value").put("kind", "exponential").put("mean", mean);
		serviceTime.put("how", how);
		double getMedian = get.get("median").doubleValue();
		inputs.putObject("cacheLinkLatencyMs")
			.put("value", PythonRound.round(Math.max(0.0, (getMedian - commandMedian) / 2), DECIMALS))
			.put("how", "(GET round trip median " + PythonFormat.format(getMedian, ".3f") + " ms - the command time's "
					+ "median " + PythonFormat.format(commandMedian, ".4f") + " ms) / 2, one way");
		return inputs;
	}

	// Redis's CPU per GET or SET: its container's at the top pass net of
	// idle (protocol v5), else per extra command between the two passes
	private static Optional<CpuPerCommand> cpuPerCommand(Experiment experiment, JsonNode passes) {
		String container = experiment.stack().equals("cache-ttl") ? "redis-ttl" : "redis";
		JsonNode idle = PythonValues.get(passes, "idle");
		if (!PythonValues.isNone(idle)) {
			return netOfIdle(passes.get("high"), idle, container);
		}
		return perExtraCommand(passes, container);
	}

	private static Optional<CpuPerCommand> netOfIdle(JsonNode high, JsonNode idle, String container) {
		double calls = Commands.of(high).calls();
		Double net = PassReadings.netCpuMs(high, idle, container);
		if (net == null || calls <= 0 || net <= 0) {
			return Optional.empty();
		}
		JsonNode perSecond = idle.path("containerCpuMsPerSecond").path(container);
		double rest = (PythonValues.isNone(perSecond) ? 0.0 : perSecond.doubleValue()) * Protocol.CALIBRATION_S;
		double cpu = net / calls;
		return Optional.of(new CpuPerCommand(cpu,
				"Redis's CPU per command at " + PythonFormat.format(high.get("rateRps").doubleValue(), "g")
						+ " req/s net of idle: (" + cpuMs(high, container) + " - " + PythonFormat.format(rest, ".0f")
						+ " ms at rest) / " + PythonFormat.format(calls, ".0f") + " GET/SET calls = "
						+ PythonFormat.format(cpu, ".4f") + " ms"));
	}

	private static Optional<CpuPerCommand> perExtraCommand(JsonNode passes, String container) {
		JsonNode high = passes.get("high");
		JsonNode low = passes.get("low");
		double calls = Commands.of(high).calls();
		double callsLow = Commands.of(low).calls();
		Double slope = PassReadings.slope(low.path("containerCpuMs").path(container),
				high.path("containerCpuMs").path(container), callsLow, calls);
		if (slope == null || slope <= 0) {
			return Optional.empty();
		}
		return Optional.of(new CpuPerCommand(slope,
				"Redis's CPU per extra command between the passes: (" + cpuMs(high, container) + " - "
						+ cpuMs(low, container) + " ms) / (" + PythonFormat.format(calls, ".0f") + " - "
						+ PythonFormat.format(callsLow, ".0f") + " GET/SET calls) = "
						+ PythonFormat.format(slope, ".4f") + " ms"));
	}

	// a container's CPU in a pass, as a whole number of ms
	private static String cpuMs(JsonNode entry, String container) {
		return PythonFormat.format(entry.path("containerCpuMs").path(container).doubleValue(), ".0f");
	}

	/**
	 * Redis's CPU per command and how it was found.
	 *
	 * @param ms the CPU per command
	 * @param how how it was found
	 */
	private record CpuPerCommand(double ms, String how) {

	}

	/**
	 * A pass's GET and SET calls and their command time from Redis's INFO commandstats;
	 * cache-ttl's service reports them in its own {@code /stats}.
	 *
	 * @param calls the GET and SET calls
	 * @param usec their command time (us)
	 */
	private record Commands(double calls, double usec) {

		static Commands of(JsonNode entry) {
			JsonNode stats = entry.has("redis") ? entry.get("redis") : entry.get("service").get("redis");
			List<JsonNode> calls = new ArrayList<>();
			List<JsonNode> usec = new ArrayList<>();
			for (String command : List.of("get", "set")) {
				JsonNode counted = stats.path(command);
				calls.add(counted.isObject() ? counted.get("calls") : PythonValues.integer(0));
				usec.add(counted.isObject() ? counted.get("usec") : PythonValues.integer(0));
			}
			return new Commands(PythonValues.sum(calls).doubleValue(), PythonValues.sum(usec).doubleValue());
		}

	}

}
