package io.archsim.model.document;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.MaxItems;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinItems;
import io.archsim.model.codec.Tag;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A replayed rate curve, such as traffic observed in telemetry: linear between its points and flat
 * after the last.
 *
 * @param points the curve, from {@code t = 0}, times rising strictly
 * @param source where it came from (and how much it was time-compressed)
 */
@Tag("trace")
@Description("""
        A replayed rate curve, e.g. observed traffic from telemetry: ``points`` are ``[t, rps]``
        pairs (seconds from the start of the run), linear between points and flat after the last.
        ``source`` says where it came from (and how much it was time-compressed).""")
public record TracePattern(
        @MinItems(2) @MaxItems(2000) List<TracePoint> points,
        @MaxLength(500) @Nullable String source) implements TrafficPattern {

    public TracePattern {
        points = List.copyOf(points);
        if (points.getFirst().t() != 0.0) {
            throw new IllegalArgumentException("a trace starts at t = 0");
        }
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i).t() <= points.get(i - 1).t()) {
                throw new IllegalArgumentException("trace times must rise strictly");
            }
        }
        for (TracePoint point : points) {
            if (!(0 <= point.rps() && point.rps() <= 100_000)) {
                throw new IllegalArgumentException("trace rates must be between 0 and 100,000 req/s");
            }
        }
    }

    /** The offered rate at {@code t} seconds: linear between points, flat after the last. */
    public double rate(double t) {
        TracePoint last = points.getLast();
        if (t >= last.t()) {
            return last.rps();
        }
        int lo = 0;
        int hi = points.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) / 2;
            if (points.get(mid).t() <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        TracePoint a = points.get(lo);
        TracePoint b = points.get(hi);
        return a.rps() + (b.rps() - a.rps()) * (t - a.t()) / (b.t() - a.t());
    }

    /** The expected arrivals from 0 to {@code until} seconds: the area under the curve. */
    public double area(double until) {
        double total = 0.0;
        for (int i = 1; i < points.size(); i++) {
            TracePoint a = points.get(i - 1);
            if (a.t() >= until) {
                break;
            }
            double end = Math.min(points.get(i).t(), until);
            total += (a.rps() + rate(end)) / 2 * (end - a.t());
        }
        TracePoint last = points.getLast();
        if (until > last.t()) {
            total += last.rps() * (until - last.t());
        }
        return total;
    }
}
