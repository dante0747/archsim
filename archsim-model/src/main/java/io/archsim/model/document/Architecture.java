package io.archsim.model.document;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.MaxItems;
import io.archsim.model.codec.OmitWhenDefault;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The topology: what exists and how requests flow.
 *
 * @param nodes the components
 * @param edges the links between them
 * @param autoSize whether parts without a CPU, memory or storage size get the size they need
 * @param host the one machine every part with a CPU size runs on; {@code null}: each its own
 */
public record Architecture(
        @Default("[]") @MaxItems(200) List<NodeSpec> nodes,
        @Default("[]") @MaxItems(1000) List<EdgeSpec> edges,

        @Default("true")
        @Description("Give parts with an empty CPU, memory or storage spec the size they need for the design's"
                + " peak load (see sizing.py). false: an empty spec is not modelled.")
        boolean autoSize,

        @OmitWhenDefault
        @Description("The one machine every part with a CPU size runs on (docker compose on one VM, a benchmark on a"
                + " laptop): their CPU work shares its physical cores. Empty: each part's vCPUs are its"
                + " own (as before schema 1.7).")
        @Nullable
        HostSpec host) {

    public Architecture {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    /** The first node with this id. */
    public Optional<NodeSpec> node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
    }
}
