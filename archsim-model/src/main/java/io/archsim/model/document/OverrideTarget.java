package io.archsim.model.document;

import io.archsim.model.codec.Description;
import org.jspecify.annotations.Nullable;

/**
 * What a scenario's override changes: a node's or an edge's config (by id), or the workload.
 *
 * @param kind node, edge or workload
 * @param id the node's or edge's id; required for those, left out for the workload
 */
public record OverrideTarget(
        TargetKind kind,

        @Description("Node or edge id; omitted for workload") @Nullable
        String id) {

    public OverrideTarget {
        if (kind != TargetKind.WORKLOAD && (id == null || id.isEmpty())) {
            throw new IllegalArgumentException("override target of kind '%s' requires an id".formatted(kind.json()));
        }
    }
}
