package io.archsim.app.runs;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.MaxLength;

/**
 * What {@code POST /projects/{id}/runs} takes.
 *
 * @param scenarioId the scenario to simulate; the design itself when not given
 * @param label what to call the run
 * @param document simulate this design (unsaved edits, say) instead of the saved one; it
 * is stored with the run, so results stay reproducible
 * @param seeds above 1, a seed group: one run per seed from the design's seed on, read
 * together with {@code GET /run-groups/{groupId}}; up to 5 in hosted workspaces, 10
 * locally. The answer is the group's first run
 * @param mode {@code sampled}: a quick check, answered from a steady sample when the
 * design qualifies; stored with the run, so a rerun uses it too
 */
record RunCreate(@MaxLength(64) @Nullable String scenarioId, @MaxLength(200) @Nullable String label,
		@Nullable ObjectNode document, @Default("1") @Ge(1) @Le(10) int seeds, @Default("full") RunMode mode) {

}
