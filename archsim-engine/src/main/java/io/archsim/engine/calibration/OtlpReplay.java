package io.archsim.engine.calibration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.OtlpCalibration.OtlpRun;
import io.archsim.engine.calibration.PrometheusReplay.Subject;
import io.archsim.engine.calibration.fit.LatencyFits;
import io.archsim.engine.calibration.otlp.TraceSummary;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.text.PythonStrings;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * The design calibrated from traces simulated at the traced request rate and compared
 * with each mapped service's traced latency (its SERVER spans, downstream calls
 * included), as the Python engine's {@code _replay} compares them. The run lasts the
 * design's duration, at least a minute and at most {@link #MAX_DURATION_S}.
 */
final class OtlpReplay {

	/** The longest replay run, in seconds: it runs inside a request. */
	static final double MAX_DURATION_S = 300.0;

	/** The fewest spans a service's latency is compared from. */
	private static final int MIN_SPANS = 20;

	private static final double MIN_DURATION_S = 60.0;

	private static final double MIN_RATE = 0.1;

	private static final double MAX_RATE = 100_000;

	private static final double NS_PER_S = 1e9;

	private static final int RATE_DIGITS = 4;

	/** A long run's warm-up is at most this share of it. */
	private static final double WARMUP_PARTS = 10;

	private static final List<String> QUANTILES = List.of("p50", "p95", "p99");

	private static final List<Double> SHARES = List.of(0.5, 0.95, 0.99);

	private final CalibrationRun run;

	private final OtlpRun otlp;

	OtlpReplay(CalibrationRun run, OtlpRun otlp) {
		this.run = run;
		this.otlp = otlp;
	}

	/**
	 * Replays the calibrated design.
	 * @param summary the traces' summary
	 * @param simulator what the design is replayed with
	 * @param limits the run's limits; {@code null} for none
	 * @return the checks and the run's result digest; none, and no digest, without SERVER
	 * spans or a span of time
	 */
	Result check(TraceSummary summary, ReplaySimulator simulator, @Nullable SimulationLimits limits) {
		double spanS = (summary.endNs() - summary.startNs()) / NS_PER_S;
		ArrayNode checks = JsonNodeFactory.instance.arrayNode();
		if (summary.serverMs().isEmpty() || spanS <= 0) {
			return new Result(checks, null);
		}
		double rate = summary.roots().size() / spanS;
		JsonNode report = simulator.simulate(replayed(rate), limits);
		Map<String, JsonNode> nodes = new HashMap<>();
		report.path("nodes").forEach((node) -> nodes.put(node.path("id").asString(), node));
		Map<String, List<Double>> byService = new TreeMap<>(PythonStrings::compare);
		byService.putAll(summary.serverMs());
		byService.forEach((service, samples) -> {
			String nodeId = this.otlp.match(service, this.otlp.mapping().services(), Set.of("service"));
			JsonNode node = (nodeId != null) ? nodes.get(nodeId) : null;
			if (node != null && samples.size() >= MIN_SPANS) {
				compare(node, service + ", at " + PythonFormat.format(rate, ".1f") + " req/s", samples, checks);
			}
		});
		return new Result(checks, report.path("provenance").path("resultDigest").asString());
	}

	/**
	 * The design as replayed: at the traced rate, constant, its duration at least a
	 * minute and at most {@link #MAX_DURATION_S}.
	 * @param rate the traced rate
	 * @return a copy of the design
	 */
	private ObjectNode replayed(double rate) {
		ObjectNode design = this.run.document().deepCopy();
		ObjectNode workload = design.withObjectProperty("workload");
		workload.put("rps", PythonRound.round(Math.min(Math.max(rate, MIN_RATE), MAX_RATE), RATE_DIGITS));
		workload.putObject("pattern").put("kind", "constant");
		double duration = Math.max(MIN_DURATION_S, workload.path("durationS").asDouble(MIN_DURATION_S));
		if (duration > MAX_DURATION_S) {
			duration = MAX_DURATION_S;
			workload.put("warmupS", Math.min(workload.path("warmupS").asDouble(0), duration / WARMUP_PARTS));
		}
		workload.put("durationS", duration);
		return design;
	}

	private static void compare(JsonNode node, String traced, List<Double> samples, ArrayNode checks) {
		JsonNode latency = node.path("summary").path("latencyMs");
		Subject subject = new Subject("component", node.path("id").asString(),
				node.path("label").asString() + " (traced as " + traced + ")",
				PythonText.truthy(latency) ? latency : JsonNodeFactory.instance.objectNode(),
				MissingNode.getInstance());
		List<Double> ordered = samples.stream().sorted().toList();
		for (int i = 0; i < QUANTILES.size(); i++) {
			JsonNode simulated = subject.latency().path(QUANTILES.get(i));
			checks.add(PrometheusReplay.check(subject, QUANTILES.get(i),
					LatencyFits.sampleQuantile(ordered, SHARES.get(i)),
					simulated.isNumber() ? simulated.doubleValue() : null));
		}
	}

	/**
	 * What a replay found.
	 *
	 * @param checks the checks
	 * @param digest the run's result digest; {@code null} when it did not run
	 */
	record Result(ArrayNode checks, @Nullable String digest) {

	}

}
