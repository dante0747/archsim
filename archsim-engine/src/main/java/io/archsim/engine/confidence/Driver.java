package io.archsim.engine.confidence;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.provenance.InputKind;
import io.archsim.engine.provenance.InputScope;

/**
 * An input to replace first to raise a result's confidence.
 *
 * @param scope a part's setting, a link's or the workload's
 * @param id the part's or link's id; {@code null} for the workload
 * @param label the part's or link's name
 * @param field the setting's JSON name
 * @param fieldLabel what the inspector calls the setting
 * @param kind where its value came from
 * @param weight its part's share of the result's weight, to three decimals; {@code null}
 * for the workload's
 */
public record Driver(InputScope scope, @Nullable String id, String label, String field, String fieldLabel,
		InputKind kind, @Nullable Double weight) {

}
