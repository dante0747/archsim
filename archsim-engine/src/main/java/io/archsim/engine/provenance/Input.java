package io.archsim.engine.provenance;

import org.jspecify.annotations.Nullable;

/**
 * One performance input of a design, with its value and where the value came from.
 * Written by {@code RecordCodec}; the workload's inputs have a {@code null} id.
 *
 * @param scope a part's setting, a link's or the workload's
 * @param id the part's or link's id; {@code null} for the workload
 * @param label the part's label, the link as {@code source → target}, or {@code Workload}
 * @param field the setting's JSON name
 * @param fieldLabel what the inspector calls the setting
 * @param value how the value reads, such as {@code lognormal, p50 8 ms / p95 25 ms}
 * @param provenance where the value came from
 */
public record Input(InputScope scope, @Nullable String id, String label, String field, String fieldLabel, String value,
		Provenance provenance) {
}
