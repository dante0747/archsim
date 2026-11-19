package io.archsim.benchmarks.inputs;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.benchmarks.values.PythonValues;

/**
 * Each staircase rate's CPU per request, net of idle, the median of its passes: the
 * service's own counter and each container's cgroup ({@code cpuByLoad}), which the load
 * curve is fitted to.
 */
public final class CpuByLoad {

	private static final int DECIMALS = 4;

	private static final double MS_PER_SECOND = 1000.0;

	private CpuByLoad() {
	}

	/**
	 * Each rate's CPU per request.
	 * @param runs the staircase's passes
	 * @param idle the idle pass
	 * @return one row per rate, in order: {@code rateRps}, {@code passes},
	 * {@code serviceCpuMsPerRequest} and {@code containerCpuMsPerRequest}
	 */
	public static ArrayNode of(List<JsonNode> runs, JsonNode idle) {
		ArrayNode rows = JsonNodeFactory.instance.arrayNode();
		double serviceAtRest = idle.get("serviceCpuMsPerSecond").doubleValue() * Protocol.CALIBRATION_S;
		for (JsonNode rate : rates(runs)) {
			List<JsonNode> at = runs.stream().filter((run) -> PythonValues.equal(run.get("rateRps"), rate)).toList();
			List<JsonNode> service = at.stream()
				.map((run) -> PythonValues
					.number(Math.max(0.0, run.at("/service/cpuMs").doubleValue() - serviceAtRest) / requests(run)))
				.toList();
			JsonNode median = PythonValues.median(service);
			ObjectNode row = rows.addObject();
			row.set("rateRps", rate);
			row.put("passes", at.size());
			row.set("serviceCpuMsPerRequest",
					PythonValues.round(PythonValues.isNone(median) ? PythonValues.number(0.0) : median, DECIMALS));
			ObjectNode containers = row.putObject("containerCpuMsPerRequest");
			at.getFirst().get("containerCpuMs").propertyNames().forEach((container) -> {
				JsonNode perRequest = PythonValues
					.median(at.stream().map((run) -> perRequest(run, idle, container)).toList());
				containers.set(container,
						PythonValues.isNone(perRequest) ? perRequest : PythonValues.round(perRequest, DECIMALS));
			});
		}
		return rows;
	}

	/**
	 * A container's busy cores in a calibration pass, net of what it burns at rest.
	 * @param pass the pass
	 * @param idle the idle pass
	 * @param container the container
	 * @return its CPU less its CPU at rest, over the pass's seconds; empty when the pass
	 * did not read it
	 */
	public static OptionalDouble netCores(JsonNode pass, JsonNode idle, String container) {
		Double net = PassReadings.netCpuMs(pass, idle, container);
		return (net != null) ? OptionalDouble.of(net / (Protocol.CALIBRATION_S * MS_PER_SECOND))
				: OptionalDouble.empty();
	}

	// the staircase's rates, each once, in order
	private static List<JsonNode> rates(List<JsonNode> runs) {
		List<JsonNode> rates = new ArrayList<>();
		for (JsonNode run : runs) {
			JsonNode rate = run.get("rateRps");
			if (rates.stream().noneMatch((seen) -> PythonValues.equal(seen, rate))) {
				rates.add(rate);
			}
		}
		rates.sort(PythonValues::compare);
		return rates;
	}

	private static JsonNode perRequest(JsonNode run, JsonNode idle, String container) {
		Double net = PassReadings.netCpuMs(run, idle, container);
		return (net != null) ? PythonValues.number(net / requests(run)) : PythonValues.none();
	}

	// max(requests, 1)
	private static double requests(JsonNode run) {
		return Math.max(run.at("/service/requests").doubleValue(), 1);
	}

}
