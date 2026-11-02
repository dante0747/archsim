package io.archsim.model.document;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.MaxLength;
import org.jspecify.annotations.Nullable;

/**
 * One comparison of a calibration's replay: what telemetry observed against what the
 * calibrated model simulates.
 *
 * @param scope the whole request path, or one component
 * @param id the component's id, for a component check
 * @param label what is compared, for people
 * @param metric which metric
 * @param observed what telemetry showed
 * @param simulated what the replay gave
 * @param error {@code (simulated - observed) / observed}
 */
@Description("One observed-against-simulated comparison of a calibration's replay.")
public record ReplayCheck(ReplayScope scope, @Nullable String id, @MaxLength(200) String label,

		@Description("Latency quantiles (ms), throughput (req/s), CPU in use (mean cores over all replicas, schema"
				+ " 1.5) or the memory peak per replica (MiB, schema 1.5)") ReplayMetric metric,

		double observed, @Nullable Double simulated,

		@Description("(simulated - observed) / observed") @Nullable Double error) {
}
