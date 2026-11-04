package io.archsim.engine.provenance;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.OmitWhenDefault;
import io.archsim.model.document.InputProvenance;

/**
 * Where one input's value came from: the kind, and what backs it up. Written by
 * {@code RecordCodec}, it lists the kind and only what it has of the rest, as the Python
 * engine writes a record without its {@code None} fields.
 *
 * @param kind measured, documented, assumed or illustrative
 * @param source where it came from: the telemetry, the documentation's link, the default
 * or preset
 * @param query the query that measured it
 * @param timeRange the time range measured
 * @param fitError the largest relative error of the fit at the checked quantiles
 * @param fittedAt when it was fitted
 * @param note a remark, such as that it was measured before and changed since
 */
public record Provenance(InputKind kind, @OmitWhenDefault @Nullable String source,
		@OmitWhenDefault @Nullable String query, @OmitWhenDefault @Nullable String timeRange,
		@OmitWhenDefault @Nullable Double fitError, @OmitWhenDefault @Nullable String fittedAt,
		@OmitWhenDefault @Nullable String note) {

	/** The source of a value that is the type's default. */
	private static final String ARCHSIM_DEFAULT = "ArchSim default";

	/**
	 * What a document's provenance record states, without the value it records.
	 * @param record the record
	 * @return its provenance
	 */
	static Provenance recorded(InputProvenance record) {
		return new Provenance(InputKind.of(record.kind()), record.source(), record.query(), record.timeRange(),
				record.fitError(), record.fittedAt(), record.note());
	}

	/**
	 * A value a technology's documentation states.
	 * @param source the documentation's link
	 * @param note which technology or preset, and that its documentation states it
	 * @return the provenance
	 */
	static Provenance documented(String source, String note) {
		return new Provenance(InputKind.DOCUMENTED, source, null, null, null, null, note);
	}

	/**
	 * A number a profile or preset fills in.
	 * @param source which preset or suggestion
	 * @return the provenance
	 */
	static Provenance illustrative(String source) {
		return new Provenance(InputKind.ILLUSTRATIVE, source, null, null, null, null, null);
	}

	/**
	 * ArchSim's default.
	 * @return the provenance
	 */
	static Provenance archSimDefault() {
		return illustrative(ARCHSIM_DEFAULT);
	}

	/**
	 * A value a person typed.
	 * @return the provenance
	 */
	static Provenance assumed() {
		return new Provenance(InputKind.ASSUMED, null, null, null, null, null, null);
	}

	/**
	 * This provenance with a note, when there is one: it replaces a note this provenance
	 * has, as the Python engine's {@code _with_note} does.
	 * @param remark the note, or {@code null} to keep this provenance as it is
	 * @return the provenance with the note
	 */
	Provenance withNote(@Nullable String remark) {
		return (remark == null || remark.isEmpty()) ? this : new Provenance(this.kind, this.source, this.query,
				this.timeRange, this.fitError, this.fittedAt, remark);
	}

}
