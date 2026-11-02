package io.archsim.model.document;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.MaxLength;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Where one numeric input came from. A measured input was fitted from telemetry, an
 * assumed one typed by a person, an illustrative one is a default or preset.
 *
 * @param kind measured, assumed or illustrative
 * @param source where a measurement came from
 * @param query the query that measured it
 * @param timeRange the time range measured
 * @param fitError the largest relative error of the fit at the checked quantiles
 * @param fittedAt when it was fitted
 * @param note a remark
 * @param value the value calibration set; the input counts as measured only while it
 * still has it (JSON {@code null} when the record does not say); a copy goes in and comes
 * out
 */
@Description("""
		Where one numeric input came from. ``measured``: fitted from telemetry (``source``,
		``query``, ``timeRange``, ``fitError``, ``fittedAt``); ``assumed``: typed by a person;
		``illustrative``: an ArchSim default or preset. Inputs without an entry are assumed when
		they differ from the default and illustrative otherwise (``provenance.py``).""")
public record InputProvenance(ProvenanceKind kind, @MaxLength(500) @Nullable String source,
		@MaxLength(2000) @Nullable String query, @MaxLength(100) @Nullable String timeRange,

		@Description("Largest relative error of the fit at the checked quantiles") @Ge(0) @Nullable Double fitError,

		@MaxLength(40) @Nullable String fittedAt, @MaxLength(500) @Nullable String note,

		@Default("null") @Description("The value calibration set (JSON, as stored at the path). The input counts as measured only"
				+ " while its current value still equals this; a scenario, sweep or edit that changes"
				+ " it makes it assumed again. Records without it (written by earlier builds) are"
				+ " taken at their word.") JsonNode value) {

	public InputProvenance {
		value = value.deepCopy();
	}

	/** The value calibration set, as a copy: a record does not change. */
	@Override
	public JsonNode value() {
		return value.deepCopy();
	}
}
