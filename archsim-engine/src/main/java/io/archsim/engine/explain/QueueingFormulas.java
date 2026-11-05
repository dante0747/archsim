package io.archsim.engine.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.TrueDivision;
import io.archsim.engine.queueing.ErlangC;
import io.archsim.engine.report.LatencyStats;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.text.NumberText;
import io.archsim.model.text.PythonFormat;

/**
 * The queueing-theory formulas behind any part's figures: its arrival rate and, for a
 * part that serves requests in slots (workers, connections, executors), the capacity of
 * its slots, their utilization, Little's law, the M/M/c waiting-time prediction and the
 * capacity of its CPU. A report lists them before the formulas of the part's own kind.
 */
public final class QueueingFormulas {

	/** Erlang C, the M/M/c formula. */
	private static final String Q_THEORY = "https://en.wikipedia.org/wiki/Erlang_(unit)#Erlang_C_formula";

	/** Little's law. */
	private static final String LITTLE = "https://en.wikipedia.org/wiki/Little%27s_law";

	/** Milliseconds in a second. */
	private static final double MS = 1000.0;

	/** The decimals a service rate is written with, from 10 to 100. */
	private static final int RATE_DIGITS = 3;

	private QueueingFormulas() {
	}

	/**
	 * The formulas of a part that does not serve requests in slots.
	 * @param node the part in a run's report
	 * @param windowS the run's measured window, in seconds
	 * @return its arrival rate, when it had arrivals in a window
	 */
	public static List<Formula> of(ReportNode node, double windowS) {
		NodeSummary summary = node.summary();
		Long arrivals = summary.arrivals();
		if (arrivals == null || arrivals == 0 || windowS == 0) {
			return List.of();
		}
		return List.of(arrivalRate(arrivals, windowS, summary.arrivalRps()));
	}

	/**
	 * The formulas of a part that serves requests in slots (workers, connections,
	 * executors).
	 * @param node the part in a run's report
	 * @param windowS the run's measured window, in seconds
	 * @return its arrival rate, when it had arrivals in a window, then its slots'
	 * formulas when it has slots, a holding time and an arrival rate
	 * @throws ArithmeticException when the M/M/c wait divides by zero, as the Python
	 * engine raises {@code ZeroDivisionError}
	 */
	public static List<Formula> ofSlots(ReportNode node, double windowS) {
		List<Formula> formulas = new ArrayList<>(of(node, windowS));
		Slots slots = Slots.of(node.summary());
		if (slots != null) {
			formulas.addAll(slots.formulas());
		}
		return formulas;
	}

	private static Formula arrivalRate(long arrivals, double windowS, @Nullable Double lambda) {
		return Formula
			.of("arrival_rate", "Arrival rate", "λ = arrivals ÷ measurement window",
					PythonFormat.format(arrivals, ",") + " ÷ " + num(windowS, 1) + " s")
			.withResult(lambda, "req/s")
			.withMetric("arrivalRps");
	}

	private static String num(@Nullable Double value, int digits) {
		return NumberText.formula(value, digits);
	}

	/**
	 * The formulas of a part that serves requests in slots.
	 *
	 * @param summary the part's figures
	 * @param slots how many requests it serves at once
	 * @param hold how long a request keeps its slot, in milliseconds
	 * @param lambda its arrival rate, in requests per second
	 */
	private record Slots(NodeSummary summary, double slots, double hold, double lambda) {

		/**
		 * The slots of a part, as far as their formulas need them.
		 * @param summary the part's figures
		 * @return its slots; none without slots, a holding time or an arrival rate, any
		 * of which is zero counting as none, as Python reads a number in a condition
		 */
		static @Nullable Slots of(NodeSummary summary) {
			Double slots = summary.slots();
			Double hold = summary.meanHoldMs();
			Double lambda = summary.arrivalRps();
			if (slots == null || hold == null || lambda == null) {
				return null;
			}
			return (slots != 0 && hold != 0 && lambda != 0) ? new Slots(summary, slots, hold, lambda) : null;
		}

		List<Formula> formulas() {
			List<Formula> formulas = new ArrayList<>(List.of(capacity(), utilization()));
			LatencyStats latency = this.summary.latencyMs();
			Double meanMs = (latency != null) ? latency.mean() : null;
			Double inSystem = this.summary.meanInSystem();
			if (meanMs != null && inSystem != null) {
				formulas.add(littlesLaw(meanMs, inSystem));
			}
			// Python's round(): halves to even
			long servers = (long) Math.rint(this.slots);
			if (this.hold > 0 && servers >= 1) {
				formulas.add(wait(Math.toIntExact(servers)));
			}
			Double serviceMs = this.summary.meanServiceMs();
			Double cpuCapacity = this.summary.cpuCapacityRps();
			if (serviceMs != null && cpuCapacity != null && serviceMs != 0 && cpuCapacity != 0) {
				formulas.add(cpuCapacity(serviceMs, cpuCapacity));
			}
			return formulas;
		}

		private Formula capacity() {
			return Formula
				.of("slot_capacity", "Capacity from slots", "capacity = slots × 1000 ÷ mean holding time",
						num(this.slots, 1) + " × 1000 ÷ " + num(this.hold, 2) + " ms")
				.withResult(this.slots * MS / this.hold, "req/s")
				.withNote("Holding time is how long a request keeps its slot (worker, connection or executor), "
						+ "including downstream calls when the slot blocks on them.");
		}

		private Formula utilization() {
			Double measured = this.summary.utilization();
			return Formula
				.of("utilization", "Utilization", "ρ = λ × mean holding time ÷ (1000 × slots)",
						num(this.lambda, 2) + " × " + num(this.hold, 2) + " ÷ (1000 × " + num(this.slots, 1) + ")")
				.withResult(this.lambda * this.hold / (MS * this.slots), "")
				.withNote((measured != null)
						? "Measured by the simulation: " + NumberText.percent(measured) + " of slot-time busy." : null)
				.withMetric("utilization");
		}

		private Formula littlesLaw(double meanMs, double inSystem) {
			return Formula
				.of("littles_law", "Little's law", "requests inside = λ × mean time inside",
						num(this.lambda, 2) + " req/s × " + num(meanMs, 2) + " ms ÷ 1000")
				.withResult(this.lambda * meanMs / MS, "requests")
				.withNote("Measured: " + num(inSystem, 2) + " requests inside on average. Little's law holds for any "
						+ "stable system, so the two should agree closely.")
				.withSource(LITTLE)
				.withMetric("inflightMean");
		}

		/**
		 * The wait for a slot as an M/M/c queue predicts it.
		 * @param servers the slots, rounded as Python rounds
		 * @return the predicted wait, or why theory predicts no steady wait
		 */
		private Formula wait(int servers) {
			double mu = MS / this.hold;
			double offered = this.lambda / mu;
			OptionalDouble waitProbability = ErlangC.waitProbability(servers, offered);
			if (waitProbability.isEmpty()) {
				return Formula
					.of("mmc_wait", "Queueing model (M/M/c)",
							"stable only if λ < slots × μ, with μ = 1000 ÷ mean holding time",
							num(this.lambda, 2) + " ≥ " + num(this.slots, 1) + " × " + num(mu, RATE_DIGITS))
					.withResult(null, "ms")
					.withNote("Arrivals exceed what the slots can serve, so the queue grows until requests are "
							+ "rejected or time out; theory predicts no steady wait.")
					.withSource(Q_THEORY)
					.withMetric("queueWaitMs");
			}
			double chance = waitProbability.getAsDouble();
			String percent = NumberText.percent(chance);
			Double measured = this.summary.meanQueueWaitMs();
			return Formula
				.of("mmc_wait", "Queueing model (M/M/c)",
						"Wq = C(c, a) ÷ (c·μ − λ),  a = λ ÷ μ,  μ = 1000 ÷ mean holding time",
						"C(" + servers + ", " + num(offered, 2) + ") = " + percent + " chance to wait; " + percent
								+ " ÷ (" + servers + " × " + num(mu, RATE_DIGITS) + " − " + num(this.lambda, 2) + ") s")
				.withResult(TrueDivision.of(chance, servers * mu - this.lambda) * MS, "ms")
				.withNote((measured != null) ? "Simulated mean wait: " + num(measured, 2) + " ms. M/M/c assumes random "
						+ "arrivals and exponential holding times; bursts, heavier tails and downstream waits push "
						+ "the real wait above it." : null)
				.withSource(Q_THEORY)
				.withMetric("queueWaitMs");
		}

		private Formula cpuCapacity(double serviceMs, double cpuCapacity) {
			return Formula
				.of("cpu_capacity", "Capacity from CPU",
						"CPU ceiling = cores × instances × 1000 ÷ CPU time per request",
						"from the CPU spec and " + num(serviceMs, 2) + " ms of work per request")
				.withResult(cpuCapacity, "req/s")
				.withMetric("cpuUtilization");
		}

	}

}
