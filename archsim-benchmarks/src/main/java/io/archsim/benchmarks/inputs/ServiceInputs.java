package io.archsim.benchmarks.inputs;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.benchmarks.machine.MachineFit;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * The service's model inputs from its calibration passes: its CPU per request (the top
 * pass net of idle under protocol v5, else the slope between two passes), shaped like its
 * handler's own time; the client-to-service latency; the low-load reference; how its CPU
 * per request changed with load; its garbage collection; and for chain the API behind the
 * gateway.
 */
final class ServiceInputs {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The share of the CPU per request the GC's pauses may take off at most. */
	private static final double KEPT_SHARE = 0.5;

	private static final int DECIMALS = 4;

	private ServiceInputs() {
	}

	/**
	 * The service's inputs.
	 * @param experiment the experiment
	 * @param passes the calibration's passes: {@code low}, {@code high}, and under
	 * protocol v5 {@code idle}, {@code staircase} and {@code cpuByLoad}
	 * @param curve how its CPU per request changes with load
	 * @param machine the design's host machine's fit, which a law of its cost is fitted
	 * for; {@code null} for none
	 * @return the inputs, each with its value and how it was found
	 */
	static ObjectNode of(Experiment experiment, JsonNode passes, CurveForm curve, @Nullable MachineFit machine) {
		JsonNode high = passes.get("high");
		JsonNode service = high.get("service");
		GcInputs.Node gc = experiment.runsOnNode() ? GcInputs.node(service) : GcInputs.Node.none();
		ObjectNode inputs = NODES.objectNode();
		inputs.set("serviceTimeMs", serviceTime(demand(experiment, passes), gc, PassReadings.timing(service, "own")));
		inputs.set("clientLinkLatencyMs", clientLatency(high.get("k6"), PassReadings.timing(service, "handler")));
		ObjectNode lowLoad = inputs.putObject("measuredLowLoad").set("rateRps", high.get("rateRps"));
		lowLoad.setAll((ObjectNode) high.get("k6"));
		LoadCurveInput.of(passes, Experiments.standard().runtime(experiment).cpuCores(), curve, machine)
			.ifPresent((byLoad) -> inputs.set("serviceTimeByLoad", byLoad));
		inputs.setAll(experiment.runsOnNode() ? gc.inputs() : GcInputs.runtime(experiment, service));
		if (experiment.stack().equals("chain")) {
			inputs.setAll(ChainInputs.of(passes));
		}
		return inputs;
	}

	// the CPU per request, less the pauses modelled as GC (Node.js), shaped like the
	// handler's own time
	private static ObjectNode serviceTime(Demand demand, GcInputs.Node gc, JsonNode own) {
		String how = demand.how();
		double mean = demand.ms();
		if (gc.perRequestMs() > 0) {
			double net = Math.max(mean - gc.perRequestMs(), KEPT_SHARE * mean);
			how += ", less the " + format(gc.perRequestMs(), ".4f") + " ms per request of the pauses modelled as GC "
					+ "(gcPauseMs) = " + format(net, ".4f") + " ms";
			mean = net;
		}
		how += "; shape (p95/median " + format(own.get("p95").doubleValue(), ".4f") + "/"
				+ format(own.get("median").doubleValue(), ".4f") + " ms) from the handler's own time";
		return Input.of(ModelDistributions.lognormalWithMean(mean, own), how);
	}

	// half what k6's median spent outside the handler, one way
	private static ObjectNode clientLatency(JsonNode k6, JsonNode handler) {
		double p50 = k6.get("p50").doubleValue();
		double handlerMedian = handler.get("median").doubleValue();
		return NODES.objectNode()
			.put("value", PythonRound.round(Math.max(0.0, (p50 - handlerMedian) / 2), DECIMALS))
			.put("how", "(k6 median " + format(p50, ".3f") + " ms - handler median " + format(handlerMedian, ".3f")
					+ " ms) / 2, one way");
	}

	// the CPU per request: the top pass net of idle (protocol v5), else the slope between
	// two passes, with the service's own figure when neither is positive
	private static Demand demand(Experiment experiment, JsonNode passes) {
		JsonNode high = passes.get("high");
		JsonNode low = passes.get("low");
		JsonNode service = high.get("service");
		JsonNode serviceLow = low.get("service");
		String who = experiment.runsOnNode() ? "Node" : "the " + Experiments.standard().runtime(experiment).label();
		JsonNode idle = PythonValues.get(passes, "idle");
		if (!PythonValues.isNone(idle)) {
			double rest = idle.get("serviceCpuMsPerSecond").doubleValue() * Protocol.CALIBRATION_S;
			double demand = Math.max(service.get("cpuMs").doubleValue() - rest, 0.0)
					/ Math.max(service.get("requests").longValue(), 1);
			double ms = (demand <= 0) ? service.get("cpuMsPerRequest").doubleValue() : demand;
			return new Demand(ms,
					"mean = " + who + "'s CPU per request at " + format(high.get("rateRps").doubleValue(), "g")
							+ " req/s net of idle: (" + format(service.get("cpuMs").doubleValue(), ".0f") + " - "
							+ format(rest, ".0f") + " ms at rest) / " + PythonText.str(service.get("requests"))
							+ " requests = " + format(ms, ".4f") + " ms");
		}
		Double slope = PassReadings.slope(serviceLow.get("cpuMs"), service.get("cpuMs"),
				serviceLow.get("requests").doubleValue(), service.get("requests").doubleValue());
		double ms = (slope != null && slope > 0) ? slope : service.get("cpuMsPerRequest").doubleValue();
		return new Demand(ms, "mean = " + who + "'s CPU per extra request between the passes: ("
				+ format(service.get("cpuMs").doubleValue(), ".0f") + " - "
				+ format(serviceLow.get("cpuMs").doubleValue(), ".0f") + " ms) / ("
				+ PythonText.str(service.get("requests")) + " - " + PythonText.str(serviceLow.get("requests"))
				+ " requests) = " + format(ms, ".4f") + " ms (at " + format(low.get("rateRps").doubleValue(), "g")
				+ " req/s alone: " + format(serviceLow.get("cpuMsPerRequest").doubleValue(), ".4f") + " ms)");
	}

	private static String format(double value, String spec) {
		return PythonFormat.format(value, spec);
	}

	/**
	 * The service's CPU per request and how it was found.
	 *
	 * @param ms the CPU per request (ms)
	 * @param how how it was found
	 */
	private record Demand(double ms, String how) {

	}

}
