package io.archsim.model.distribution;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.MaxItems;
import io.archsim.model.codec.MinItems;
import io.archsim.model.codec.Tag;
import io.archsim.model.text.PythonFormat;
import java.util.List;

/**
 * A measured distribution: a quantile table, linear between its points. The probabilities rise
 * strictly from 0 to 1 (both ends included) and the values never fall, as read off a latency
 * histogram's buckets.
 */
@Tag("empirical")
@Description("""
        A measured distribution: a quantile table, linear between its points.

        ``quantiles`` holds ``[p, ms]`` pairs: ``p`` rises strictly from 0 to 1 (both ends included)
        and the values never fall, e.g. read off a latency histogram's buckets. Sampling draws ``u``
        from the seeded RNG and interpolates, so runs stay reproducible.""")
public record Empirical(@MinItems(2) @MaxItems(200) List<Quantile> quantiles) implements Distribution {

    public Empirical {
        quantiles = List.copyOf(quantiles);
        if (quantiles.getFirst().p() != 0.0 || quantiles.getLast().p() != 1.0) {
            throw new IllegalArgumentException("empirical quantiles must start at p = 0 and end at p = 1");
        }
        for (int i = 1; i < quantiles.size(); i++) {
            if (quantiles.get(i).p() <= quantiles.get(i - 1).p()) {
                throw new IllegalArgumentException("empirical quantile probabilities must rise strictly");
            }
        }
        for (int i = 1; i < quantiles.size(); i++) {
            if (quantiles.get(i).ms() < quantiles.get(i - 1).ms()) {
                throw new IllegalArgumentException("empirical quantile values must not fall");
            }
        }
        if (quantiles.getFirst().ms() < 0 || quantiles.getLast().ms() > MAX_SAMPLE_MS) {
            throw new IllegalArgumentException(
                    "empirical values must be between 0 and %s ms".formatted(PythonFormat.format(MAX_SAMPLE_MS, "g")));
        }
    }

    /** The value at probability {@code q}, linear between the table's points. */
    public double quantile(double q) {
        int i = Math.min(Math.max(bisectRight(q) - 1, 0), quantiles.size() - 2);
        Quantile lo = quantiles.get(i);
        Quantile hi = quantiles.get(i + 1);
        return lo.ms() + (hi.ms() - lo.ms()) * (q - lo.p()) / (hi.p() - lo.p());
    }

    /** Python's {@code bisect_right} on the probabilities: how many are at most {@code q}. */
    private int bisectRight(double q) {
        int lo = 0;
        int hi = quantiles.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (q < quantiles.get(mid).p()) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    @Override
    public double expected() {
        double sum = 0;
        for (int i = 1; i < quantiles.size(); i++) {
            Quantile lo = quantiles.get(i - 1);
            Quantile hi = quantiles.get(i);
            sum += (hi.p() - lo.p()) * (lo.ms() + hi.ms()) / 2;
        }
        return sum;
    }

    @Override
    public String describe() {
        return "empirical, p50 %s ms / p95 %s ms (%d points)"
                .formatted(
                        PythonFormat.format(quantile(0.5), ".4g"),
                        PythonFormat.format(quantile(0.95), ".4g"),
                        quantiles.size());
    }
}
