package io.archsim.model.document;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.MaxLength;
import org.jspecify.annotations.Nullable;

/**
 * A directed link from {@code source.sourcePort} to {@code target.targetPort}: requests flow
 * along it from the caller to the callee.
 *
 * @param id unique among the edges
 * @param source the calling node's id
 * @param sourcePort an output port of the source
 * @param target the called node's id
 * @param targetPort an input port of the target
 * @param label the display name
 * @param config how requests cross the link
 */
@Description("A directed routing/network link from ``source.sourcePort`` to ``target.targetPort``.")
public record EdgeSpec(
        @Matches(Patterns.ID) String id,
        String source,
        @Default("out") String sourcePort,
        String target,
        @Default("in") String targetPort,
        @MaxLength(120) @Nullable String label,
        @Default("{}") EdgeConfig config) {

    /** The label, or the id when there is none. */
    public String name() {
        return label == null || label.isEmpty() ? id : label;
    }
}
