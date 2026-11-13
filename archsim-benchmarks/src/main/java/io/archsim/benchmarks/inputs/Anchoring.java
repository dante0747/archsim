package io.archsim.benchmarks.inputs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;

/**
 * Makes the model reproduce the calibration pass itself: first the service time's spread,
 * then its median. The client-to-service latency is the one input not measured directly;
 * it absorbs the difference between the measured median and the model's median at that
 * rate (simulated with no client latency), floored at zero. Without this the model counts
 * the database driver's CPU twice (in the CPU per request and inside the measured query
 * time) and starts about 2x too slow (run 2).
 */
final class Anchoring {

	/** Seconds simulated per try of the spread. */
	static final int SPREAD_S = 10;

	/** Bisection steps on the log of the p95/median ratio. */
	static final int SPREAD_STEPS = 6;

	/** The spread stops once the model's p95 - p50 is within this share of k6's. */
	static final double SPREAD_TOLERANCE = 0.05;

	private static final int DECIMALS = 4;

	private static final String THREE = ".3f";

	private static final String TWO = ".2f";

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final DesignSimulation simulation;

	Anchoring(DesignSimulation simulation) {
		this.simulation = simulation;
	}

	/**
	 * Anchors the inputs at the top pass: the spread, then the client latency.
	 * @param experiment the experiment
	 * @param inputs the inputs, changed in place
	 * @param high the top pass
	 */
	void anchor(Experiment experiment, ObjectNode inputs, JsonNode high) {
		anchorSpread(experiment, inputs, high);
		JsonNode rate = high.get("rateRps");
		ObjectNode probe = inputs.deepCopy();
		probe.putObject("clientLinkLatencyMs").put("value", 0.0);
		ObjectNode simulated = this.simulation.at(experiment, probe, rate,
				new Designs.Window(Protocol.WARMUP_S, Protocol.CALIBRATION_S));
		JsonNode measured = high.get("k6").get("p50");
		double modelP50 = PythonValues.numberOrZero(simulated, "p50");
		double oneWay = Math.max(0.0, (measured.doubleValue() - modelP50) / 2);
		inputs.putObject("clientLinkLatencyMs")
			.put("value", PythonRound.round(oneWay, DECIMALS))
			.put("how",
					"anchored: (measured median " + PythonFormat.format(measured.doubleValue(), THREE)
							+ " ms - the model's median without it " + PythonFormat.format(modelP50, THREE) + " ms at "
							+ PythonFormat.format(rate.doubleValue(), "g") + " req/s) / 2, one way, at least 0");
		inputs.putObject("anchor")
			.set("rateRps", rate)
			.set("measuredP50", measured)
			.set("modelP50WithoutClientLatency", PythonValues.get(simulated, "p50"))
			.put("residualMs", PythonRound.round(modelP50 + 2 * oneWay - measured.doubleValue(), DECIMALS));
	}

	/**
	 * Narrows the service time's spread until the model's p95 - p50 at the top pass is
	 * k6's, keeping its mean (the CPU per request). Kept when the model's is no wider
	 * than k6's: a tail the model misses can come from the database, the broker or the
	 * network, so the spread is never widened. Bisection on the log of the p95/median
	 * ratio, each try a short simulation at the pass's rate without the client latency,
	 * which moves p95 and p50 alike.
	 * @param experiment the experiment
	 * @param inputs the inputs, the service time changed in place
	 * @param high the top pass
	 */
	void anchorSpread(Experiment experiment, ObjectNode inputs, JsonNode high) {
		JsonNode serviceTime = inputs.path("serviceTimeMs").path("value");
		JsonNode k6 = high.path("k6");
		if (!"lognormal".equals(serviceTime.path("kind").stringValue("")) || PythonValues.isNone(k6.path("p95"))
				|| PythonValues.isNone(k6.path("p50"))) {
			return;
		}
		Spread spread = new Spread(experiment, inputs, high.get("rateRps"), ModelDistributions.mean(serviceTime));
		double target = k6.get("p95").doubleValue() - k6.get("p50").doubleValue();
		double widest = serviceTime.get("p95").doubleValue() / serviceTime.get("median").doubleValue();
		if (widest <= ModelDistributions.MIN_SPREAD) {
			return;
		}
		Try first = new Try(widest, spread.at(widest));
		if (first.model() <= target) {
			return;
		}
		Try closest = closest(spread, target, first);
		if (closest.ratio() == widest) {
			// no narrower spread came closer: the tail is not the service's
			return;
		}
		String how = inputs.get("serviceTimeMs").get("how").stringValue() + "; spread anchored: p95/median "
				+ PythonFormat.format(closest.ratio(), TWO) + " instead of " + PythonFormat.format(widest, TWO)
				+ ", so that the model's p95 - p50 at " + PythonFormat.format(spread.rate().doubleValue(), "g")
				+ " req/s is k6's: " + PythonFormat.format(closest.model(), THREE) + " against "
				+ PythonFormat.format(target, THREE) + " ms";
		inputs.putObject("serviceTimeMs").set("value", spread.serviceTime(closest.ratio())).put("how", how);
	}

	// bisection on the log of the ratio; the closest try wins
	private static Try closest(Spread spread, double target, Try widest) {
		double low = StrictMath.log(ModelDistributions.MIN_SPREAD);
		double high = StrictMath.log(widest.ratio());
		Try closest = widest;
		for (int step = 0; step < SPREAD_STEPS; step++) {
			double middle = (low + high) / 2;
			double got = spread.at(StrictMath.exp(middle));
			if (Math.abs(got - target) < Math.abs(closest.model() - target)) {
				closest = new Try(StrictMath.exp(middle), got);
			}
			if (Math.abs(got - target) <= SPREAD_TOLERANCE * target) {
				break;
			}
			if (got > target) {
				high = middle;
			}
			else {
				low = middle;
			}
		}
		return closest;
	}

	/**
	 * One try of the spread.
	 *
	 * @param ratio the service time's p95 over its median
	 * @param model the model's p95 - p50 with it (ms)
	 */
	private record Try(double ratio, double model) {

	}

	/**
	 * The model's p95 - p50 at the pass's rate for a service time of the same mean and
	 * another spread, without the client latency, which moves p95 and p50 alike.
	 */
	private final class Spread {

		private final Experiment experiment;

		private final ObjectNode inputs;

		private final JsonNode rate;

		private final double mean;

		Spread(Experiment experiment, ObjectNode inputs, JsonNode rate, double mean) {
			this.experiment = experiment;
			this.inputs = inputs;
			this.rate = rate;
			this.mean = mean;
		}

		JsonNode rate() {
			return this.rate;
		}

		ObjectNode serviceTime(double ratio) {
			return ModelDistributions.lognormalWithMean(this.mean,
					NODES.objectNode().put("median", 1.0).put("p95", ratio));
		}

		double at(double ratio) {
			ObjectNode probe = this.inputs.deepCopy();
			probe.putObject("serviceTimeMs").set("value", serviceTime(ratio));
			probe.putObject("clientLinkLatencyMs").put("value", 0.0);
			ObjectNode simulated = Anchoring.this.simulation.at(this.experiment, probe, this.rate,
					new Designs.Window(Protocol.WARMUP_S, SPREAD_S));
			return PythonValues.numberOrZero(simulated, "p95") - PythonValues.numberOrZero(simulated, "p50");
		}

	}

}
