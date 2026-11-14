package io.archsim.model.document;

import java.util.HashSet;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.DefaultFrom;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.MaxItems;
import io.archsim.model.codec.OmitWhenDefault;
import io.archsim.model.codec.Ui;

/**
 * The synthetic traffic the clients send: how much, for how long, of which kind, over
 * which keys, and the faults injected meanwhile.
 *
 * @param rps the baseline offered load, in requests per second
 * @param durationS the arrival window, in seconds
 * @param warmupS the start of the run left out of the statistics, shorter than the window
 * @param seed the random seed
 * @param readRatio the share of requests that are reads
 * @param arrivalProcess random or evenly spaced arrivals
 * @param pattern the shape of the load over time
 * @param keyCount the distinct keys requests touch
 * @param keySkew the Zipf exponent of key popularity (0 = uniform)
 * @param drainS time after arrivals stop for in-flight requests; {@code null}: automatic
 * @param faults the faults injected during the run, with unique ids
 * @param peakHoursPerMonth the hours a month at the pattern's peak, the rest of the month
 * at its base load, for the monthly cost (ADR-0074)
 */
@Description("Synthetic traffic driving the (single) traffic source.")
public record Workload(
		@Default("100.0") @Description(Descriptions.WORKLOAD_RPS) @Ui(label = "Baseline load", unit = "req/s",
				group = Descriptions.WORKLOAD_TRAFFIC_GROUP, assumption = true) @Ge(0) @Le(100_000) double rps,
		@Default("60.0") @Description(Descriptions.WORKLOAD_DURATION_S) @Ui(label = "Duration", unit = "s",
				group = Descriptions.WORKLOAD_RUN_GROUP) @Gt(0) @Le(3600) double durationS,
		@Default("10.0") @Description(Descriptions.WORKLOAD_WARMUP_S) @Ui(label = "Warm-up", unit = "s",
				group = Descriptions.WORKLOAD_RUN_GROUP) @Ge(0) @Le(3600) double warmupS,
		@Default("42") @Description(Descriptions.WORKLOAD_SEED) @Ui(label = "Seed",
				group = Descriptions.WORKLOAD_RUN_GROUP) @Ge(0) @Le(MAX_SEED) long seed,
		@Default("0.9") @Description(Descriptions.WORKLOAD_READ_RATIO) @Ui(label = "Read ratio",
				group = Descriptions.WORKLOAD_TRAFFIC_GROUP, assumption = true,
				step = 0.05) @Ge(0) @Le(1) double readRatio,
		@Default("poisson") @Description(Descriptions.WORKLOAD_ARRIVAL_PROCESS) @Ui(label = "Arrival process",
				group = Descriptions.WORKLOAD_TRAFFIC_GROUP) ArrivalProcess arrivalProcess,
		@DefaultFrom("CONSTANT_LOAD") @Description(Descriptions.WORKLOAD_PATTERN) @Ui(label = "Traffic pattern",
				kind = "pattern", group = Descriptions.WORKLOAD_TRAFFIC_GROUP) TrafficPattern pattern,
		@Default("100000") @Description(Descriptions.WORKLOAD_KEY_COUNT) @Ui(label = "Distinct keys",
				group = Descriptions.WORKLOAD_KEYSPACE_GROUP, assumption = true) @Ge(1) @Le(1_000_000) int keyCount,
		@Default("0.99") @Description(Descriptions.WORKLOAD_KEY_SKEW) @Ui(label = "Key skew (Zipf s)",
				group = Descriptions.WORKLOAD_KEYSPACE_GROUP, assumption = true,
				step = 0.05) @Ge(0) @Le(3) double keySkew,
		@Description(Descriptions.WORKLOAD_DRAIN_S) @Ui(label = "Drain time", unit = "s",
				group = Descriptions.WORKLOAD_RUN_GROUP) @Ge(0) @Le(600) @Nullable Double drainS,
		@Default("[]") @Description(Descriptions.WORKLOAD_FAULTS) @Ui(label = "Faults", kind = "faults",
				group = Descriptions.WORKLOAD_CHAOS_GROUP, hidden = true) @MaxItems(20) List<Fault> faults,
		@Default("0.0") @OmitWhenDefault @Description(Descriptions.WORKLOAD_PEAK_HOURS) @Ui(
				label = "Peak hours a month", unit = "h",
				group = Descriptions.WORKLOAD_TRAFFIC_GROUP) @Ge(0) @Le(730) double peakHoursPerMonth) {

	/** The largest seed the engine takes: 2<sup>32</sup> - 1. */
	public static final long MAX_SEED = 4_294_967_295L;

	/** The default traffic pattern: the baseline load throughout. */
	static final TrafficPattern CONSTANT_LOAD = new ConstantPattern();

	/**
	 * Checks what spans fields: the warm-up ends inside the window, a burst starts inside
	 * it, and fault ids are unique.
	 */
	public Workload {
		faults = List.copyOf(faults);
		if (warmupS >= durationS) {
			throw new IllegalArgumentException("warmupS must be shorter than durationS");
		}
		if (pattern instanceof BurstPattern burst && burst.startS() >= durationS) {
			throw new IllegalArgumentException("burst startS must be within durationS");
		}
		if (new HashSet<>(faults.stream().map(Fault::id).toList()).size() != faults.size()) {
			throw new IllegalArgumentException("fault ids must be unique");
		}
	}

	/**
	 * The offered rate at a moment of the run.
	 * @param seconds the moment, in seconds from the start of the run
	 * @return the rate, in requests per second
	 */
	public double rateAt(double seconds) {
		return switch (this.pattern) {
			case TracePattern trace -> trace.rate(seconds);
			case RampPattern ramp -> {
				double fraction = Math.min(Math.max(seconds / this.durationS, 0.0), 1.0);
				yield ramp.startRps() + (ramp.endRps() - ramp.startRps()) * fraction;
			}
			case BurstPattern burst -> burst.active(seconds) ? burst.burstRps() : this.rps;
			case ConstantPattern() -> this.rps;
		};
	}

	/**
	 * The highest offered rate within the arrival window.
	 * @return the rate, in requests per second
	 */
	public double peakRate() {
		return switch (this.pattern) {
			case TracePattern trace -> {
				double peak = trace.rate(this.durationS);
				for (TracePoint point : trace.points()) {
					if (point.t() <= this.durationS) {
						peak = Math.max(peak, point.rps());
					}
				}
				yield peak;
			}
			case RampPattern ramp -> Math.max(ramp.startRps(), ramp.endRps());
			case BurstPattern burst -> Math.max(this.rps, burst.burstRps());
			case ConstantPattern() -> this.rps;
		};
	}

	/**
	 * The expected number of arrivals over the whole arrival window.
	 * @return the expected number of requests
	 */
	public double expectedArrivals() {
		return switch (this.pattern) {
			case TracePattern trace -> trace.area(this.durationS);
			case RampPattern ramp -> (ramp.startRps() + ramp.endRps()) / 2.0 * this.durationS;
			case BurstPattern burst -> {
				double inside = Math.max(0.0, Math.min(burst.durationS(), this.durationS - burst.startS()));
				yield this.rps * (this.durationS - inside) + burst.burstRps() * inside;
			}
			case ConstantPattern() -> this.rps * this.durationS;
		};
	}

}
