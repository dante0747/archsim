package io.archsim.engine.provenance;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.model.document.InputProvenance;
import io.archsim.model.document.ProvenanceKind;

/**
 * What a document's provenance record says about an input, once checked against the
 * input's value: the record itself, or, for a measured input whose value has changed
 * since, a note saying so (the input is then classified as if it had no record).
 *
 * @param provenance the record's provenance; {@code null} when there is no record or it
 * no longer holds
 * @param note the note for an input measured before; {@code null} for none
 */
record Recorded(@Nullable Provenance provenance, @Nullable String note) {

	/** No record. */
	private static final Recorded NONE = new Recorded(null, null);

	/**
	 * Checks a record against the input's value.
	 * @param record the document's record for the input; {@code null} for none
	 * @param current the input's value now
	 * @return what the record says
	 */
	static Recorded of(@Nullable InputProvenance record, JsonNode current) {
		if (record == null) {
			return NONE;
		}
		JsonNode measured = record.value();
		if (record.kind() == ProvenanceKind.MEASURED && !Values.isNone(measured) && !Values.same(measured, current)) {
			return new Recorded(null, "changed since it was measured (" + where(record) + ")");
		}
		return new Recorded(Provenance.recorded(record), null);
	}

	/**
	 * The input's provenance: the record's when it holds, else the classification, with
	 * the note that it was measured before when it was.
	 * @param unrecorded classifies the input as if it had no record
	 * @return the provenance
	 */
	Provenance orClassify(Supplier<Provenance> unrecorded) {
		Provenance stated = this.provenance;
		return (stated != null) ? stated : unrecorded.get().withNote(this.note);
	}

	/**
	 * Where and when it was measured, as far as the record says.
	 * @param record the record
	 * @return its source and time range, or {@code telemetry}
	 */
	private static String where(InputProvenance record) {
		String where = Stream.of(record.source(), record.timeRange())
			.filter(Objects::nonNull)
			.filter((text) -> !text.isEmpty())
			.collect(Collectors.joining(", "));
		return where.isEmpty() ? "telemetry" : where;
	}

}
