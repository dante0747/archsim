package io.archsim.model.document;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.MaxItems;
import io.archsim.model.codec.SchemaExtra;
import java.util.HashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The synthetic traffic the clients send: how much, for how long, of which kind, over which keys,
 * and the faults injected meanwhile.
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
 */
@Description("Synthetic traffic driving the (single) traffic source.")
public record Workload(
        @Default("100.0") @Description("Baseline offered load (requests/s)") @SchemaExtra("""
                        {"x-ui": {"label": "Baseline load", "unit": "req/s", "group": "Traffic",
                                  "assumption": true}}""") @Ge(0) @Le(100_000)
        double rps,

        @Default("60.0")
        @Description("Arrival window length (s)")
        @SchemaExtra("{\"x-ui\": {\"label\": \"Duration\", \"unit\": \"s\", \"group\": \"Run\"}}")
        @Gt(0)
        @Le(3600)
        double durationS,

        @Default("10.0")
        @Description("Initial period excluded from summary statistics (s)")
        @SchemaExtra("{\"x-ui\": {\"label\": \"Warm-up\", \"unit\": \"s\", \"group\": \"Run\"}}")
        @Ge(0)
        @Le(3600)
        double warmupS,

        @Default("42")
        @Description("Random seed for reproducibility")
        @SchemaExtra("{\"x-ui\": {\"label\": \"Seed\", \"group\": \"Run\"}}")
        @Ge(0)
        @Le(4_294_967_295L)
        long seed,

        @Default("0.9") @Description("Fraction of requests that are reads") @SchemaExtra("""
                        {"x-ui": {"label": "Read ratio", "group": "Traffic", "assumption": true, "step": 0.05}}""") @Ge(0) @Le(1)
        double readRatio,

        @Default("poisson")
        @Description("poisson: random (exponential) inter-arrivals; deterministic: evenly spaced")
        @SchemaExtra("{\"x-ui\": {\"label\": \"Arrival process\", \"group\": \"Traffic\"}}")
        ArrivalProcess arrivalProcess,

        @Default("{\"kind\": \"constant\"}")
        @Description("Shape of the offered load over time")
        @SchemaExtra("{\"x-ui\": {\"label\": \"Traffic pattern\", \"kind\": \"pattern\", \"group\": \"Traffic\"}}")
        TrafficPattern pattern,

        @Default("100000")
        @Description("Number of distinct keys requests touch (used by TTL cache models)")
        @SchemaExtra("""
                        {"x-ui": {"label": "Distinct keys", "group": "Keyspace", "assumption": true}}""")
        @Ge(1)
        @Le(1_000_000)
        int keyCount,

        @Default("0.99")
        @Description("Zipf exponent of key popularity (0 = uniform, ~1 = typical web skew)")
        @SchemaExtra("""
                        {"x-ui": {"label": "Key skew (Zipf s)", "group": "Keyspace", "assumption": true,
                                  "step": 0.05}}""")
        @Ge(0)
        @Le(3)
        double keySkew,

        @Description("Time after arrivals stop to let in-flight requests finish (s). Empty = automatic.")
        @SchemaExtra("{\"x-ui\": {\"label\": \"Drain time\", \"unit\": \"s\", \"group\": \"Run\"}}")
        @Ge(0)
        @Le(600)
        @Nullable
        Double drainS,

        @Default("[]")
        @Description("Faults injected during the run (outages, slowdowns, network latency)")
        @SchemaExtra("""
                        {"x-ui": {"label": "Faults", "kind": "faults", "group": "Chaos", "hidden": true}}""")
        @MaxItems(20)
        List<Fault> faults) {

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

    /** The offered rate at {@code t} seconds into the run, in requests per second. */
    public double rateAt(double t) {
        return switch (pattern) {
            case TracePattern trace -> trace.rate(t);
            case RampPattern ramp -> {
                double fraction = Math.min(Math.max(t / durationS, 0.0), 1.0);
                yield ramp.startRps() + (ramp.endRps() - ramp.startRps()) * fraction;
            }
            case BurstPattern burst when burst.active(t) -> burst.burstRps();
            case BurstPattern _ -> rps;
            case ConstantPattern _ -> rps;
        };
    }

    /** The highest offered rate within the arrival window, in requests per second. */
    public double peakRate() {
        return switch (pattern) {
            case TracePattern trace -> {
                double peak = trace.rate(durationS);
                for (TracePoint point : trace.points()) {
                    if (point.t() <= durationS) {
                        peak = Math.max(peak, point.rps());
                    }
                }
                yield peak;
            }
            case RampPattern ramp -> Math.max(ramp.startRps(), ramp.endRps());
            case BurstPattern burst -> Math.max(rps, burst.burstRps());
            case ConstantPattern _ -> rps;
        };
    }

    /** The expected number of arrivals over the whole arrival window. */
    public double expectedArrivals() {
        return switch (pattern) {
            case TracePattern trace -> trace.area(durationS);
            case RampPattern ramp -> (ramp.startRps() + ramp.endRps()) / 2.0 * durationS;
            case BurstPattern burst -> {
                double inside = Math.max(0.0, Math.min(burst.durationS(), durationS - burst.startS()));
                yield rps * (durationS - inside) + burst.burstRps() * inside;
            }
            case ConstantPattern _ -> rps * durationS;
        };
    }
}
