package io.archsim.engine.components.service;

import java.util.ArrayList;
import java.util.List;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.settings.LoadCurve;
import io.archsim.engine.text.NumberText;
import io.archsim.model.component.service.GcPauses;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.component.service.Warmup;
import io.archsim.model.text.PythonFormat;

/**
 * The calculations behind a service's own figures: its service time at the load it
 * carried, an event loop's capacity, the time its garbage collector pauses it and its
 * warm-up.
 */
final class ServiceExplain {

	private static final double MS_PER_SECOND = 1000.0;

	/**
	 * The mean of a pause, in medians: about 1.2 for a log-normal with its 95th at 2.5
	 * medians.
	 */
	private static final double PAUSE_MEAN = 1.2;

	/**
	 * The places a load curve's figures (a time at factor 1, a factor) are written to.
	 */
	private static final int CURVE_DIGITS = 3;

	/** The places a share of full speed on a machine is written to. */
	private static final int SPEED_SHARE_DIGITS = 3;

	private ServiceExplain() {
	}

	/**
	 * A service's formulas, in the report's order.
	 * @param service the service
	 * @param summary its summary in the report
	 * @return the formulas of what is modelled: a load curve, an event loop, pauses, a
	 * warm-up
	 */
	static List<Formula> of(ServiceState service, NodeSummary summary) {
		List<Formula> formulas = new ArrayList<>();
		Double mean = summary.meanServiceMs();
		double serviceMs = (mean != null) ? mean : 0.0;
		if (serviceMs != 0 && !service.curve().isEmpty()) {
			formulas.add(loadCurve(service, serviceMs, arrivalRps(summary)));
		}
		if (service.config().runsEventLoop() && serviceMs != 0) {
			formulas.add(eventLoop(service, serviceMs, arrivalRps(summary)));
		}
		GcPauses pauses = service.gc();
		if (pauses != null) {
			formulas.add(gcShare(service, pauses, arrivalRps(summary)));
		}
		Warmup warmup = service.warmup();
		if (warmup != null) {
			formulas.add(Formula
				.of("warmup", "JIT warm-up", "slowdown(t) = 1 + (factor − 1) × max(0, 1 − age ÷ warm-up time)",
						"factor " + num(warmup.factor(), 2) + ", warm-up " + num(warmup.durationMs() / MS_PER_SECOND, 1)
								+ " s")
				.withResult(warmup.factor(), "x")
				.withNote("Applies to replicas started during the run (autoscaling) and to new serverless instances; "
						+ "it makes scale-out slower to help.")
				.withMetric("warmingReplicas"));
		}
		return formulas;
	}

	private static Formula loadCurve(ServiceState service, double serviceMs, double arrivalRps) {
		LoadCurve curve = service.curve();
		double base = service.baseServiceMs(serviceMs);
		double perReplica = arrivalRps / Math.max(1.0, service.replicasMean());
		double factor = curve.factorAt(perReplica);
		return Formula
			.of("load_curve", "Service time at this load",
					"service time = service time at factor 1 × factor(req/s per replica)",
					num(base, CURVE_DIGITS) + " ms × " + num(factor, CURVE_DIGITS) + " at " + num(perReplica, 1)
							+ " req/s per replica")
			.withResult(base * factor, "ms")
			.withNote("The factor follows each replica's arrivals over the last second: " + curve.describe()
					+ ", interpolated in log(req/s) and flat outside the points.");
	}

	// the loops' capacity: each runs only the CPU part of a request, at the speed of its
	// machine's threads as the machine fills
	private static Formula eventLoop(ServiceState service, double serviceMs, double arrivalRps) {
		ServiceConfig config = service.config();
		LoadCurve curve = service.curve();
		double loops = service.perReplica() * service.replicasMean();
		double perLoop = curve.capacityRps(MS_PER_SECOND / service.baseServiceMs(serviceMs));
		String at = curve.isEmpty() ? ""
				: " (at the factor of that rate, ×" + num(curve.factorAt(perLoop), CURVE_DIGITS) + ")";
		double slowed = service.machine().capacity(perLoop, 1.0, arrivalRps / Math.max(1.0, loops));
		if (perLoop > 0 && slowed < perLoop) {
			at += " at " + num(slowed / perLoop, SPEED_SHARE_DIGITS) + " of full speed on its machine";
			perLoop = slowed;
		}
		String cpuShare = (config.cpuCores() != null && config.cpuShare() < 1) ? " The whole service time holds the "
				+ "loop; " + PythonFormat.format(config.cpuShare(), ".0%") + " of it (the CPU share) counts as CPU use."
				: "";
		return Formula
			.of("event_loop", "Event-loop capacity",
					"capacity = replicas × processes × 1000 ÷ CPU time per request" + at,
					num(service.replicasMean(), 1) + " × " + service.perReplica() + " × 1000 ÷ "
							+ num(MS_PER_SECOND / perLoop, 2) + " ms")
			.withResult(loops * perLoop, "req/s")
			.withNote("Only the CPU part of a request occupies the loop; time waiting on downstream calls does not "
					+ "count." + cpuShare)
			.withMetric("eventLoopLagMs");
	}

	private static Formula gcShare(ServiceState service, GcPauses pauses, double arrivalRps) {
		double perReplica = arrivalRps / Math.max(1.0, service.replicasMean());
		double share = perReplica / pauses.everyRequests() * pauses.pauseMs() * PAUSE_MEAN / MS_PER_SECOND;
		return Formula
			.of("gc_share", "Time lost to GC pauses",
					"paused share ≈ (requests/s per replica ÷ requests per GC) × mean pause ÷ 1000",
					"(" + num(perReplica, 2) + " ÷ " + PythonFormat.format(pauses.everyRequests(), ",") + ") × "
							+ num(pauses.pauseMs() * PAUSE_MEAN, 2) + " ms ÷ 1000")
			.withResult(share, "")
			.withNote("During a pause every request on that replica stalls, which shows up in the latency tail more "
					+ "than in the mean.")
			.withMetric("gcPauseMs");
	}

	private static double arrivalRps(NodeSummary summary) {
		Double rps = summary.arrivalRps();
		return (rps != null) ? rps : 0.0;
	}

	private static String num(double value, int digits) {
		return NumberText.formula(value, digits);
	}

}
