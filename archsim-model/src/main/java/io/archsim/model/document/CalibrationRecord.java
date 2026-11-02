package io.archsim.model.document;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.MaxItems;
import io.archsim.model.codec.MaxLength;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The last calibration from telemetry, and how well the calibrated model replays what was
 * seen.
 *
 * @param at when it ran
 * @param source where the telemetry came from
 * @param timeRange the time range it covered
 * @param inputs how many inputs it set
 * @param replay the replay's comparisons
 * @param replayDigest the replay report's digest
 * @param notes remarks
 */
@Description("The last calibration from telemetry, and how well the calibrated model replays it.")
public record CalibrationRecord(@MaxLength(40) String at, @MaxLength(500) String source,
		@MaxLength(100) @Nullable String timeRange, @Description("Inputs set from telemetry") @Ge(0) long inputs,
		@Default("[]") @MaxItems(500) List<ReplayCheck> replay, @MaxLength(80) @Nullable String replayDigest,
		@Default("[]") @MaxItems(50) List<String> notes) {

	public CalibrationRecord {
		replay = List.copyOf(replay);
		notes = List.copyOf(notes);
	}
}
