package io.archsim.model.component.loadbalancer;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.component.Port;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.issue.Issue;
import io.archsim.model.text.PythonFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A load balancer: it spreads requests across the replicas of the targets its links lead to, and
 * links can filter by request type.
 */
public final class LoadBalancerSpec extends ComponentSpec<LoadBalancerConfig> {

    public LoadBalancerSpec() {
        super(
                "load_balancer",
                "Load Balancer",
                Category.NETWORK,
                LoadBalancerConfig.class,
                List.of(
                        Port.in("in", "Requests", "Incoming requests."),
                        Port.out(
                                        "out",
                                        "Targets",
                                        "Instances to balance across. A retry (link max attempts > 1) avoids the"
                                                + " instance that just failed.")
                                .withMinEdges(1)));
    }

    @Override
    public List<Issue> validate(NodeSpec node, LoadBalancerConfig config, GraphView view) {
        List<Issue> issues = new ArrayList<>();
        List<EdgeSpec> outs = view.outEdges(node.id());
        if (outs.isEmpty()) {
            return issues; // the missing link is reported for every port alike
        }
        Optional<Double> upstream = config.effectiveUpstreamTimeoutMs();
        if (upstream.isPresent()) {
            for (EdgeSpec edge : outs) {
                Double timeout = edge.config().timeoutMs();
                if (timeout != null && timeout > upstream.get()) {
                    issues.add(Issue.warning(
                                    "lb.timeout_above_upstream",
                                    ("Link '%s' allows %s ms but %s gives up after %s ms (its upstream timeout),"
                                                    + " so the longer link timeout never applies.")
                                            .formatted(
                                                    edge.id(),
                                                    PythonFormat.format(timeout, "g"),
                                                    node.name(),
                                                    PythonFormat.format(upstream.get(), "g")))
                            .atNode(node.id())
                            .atEdge(edge.id())
                            .atPath("upstreamTimeoutMs"));
                }
            }
        }
        Set<RequestType> incoming = EnumSet.noneOf(RequestType.class);
        incoming.addAll(view.incomingTypes().getOrDefault(node.id(), Set.of()));
        for (RequestType op : incoming) {
            if (outs.stream().noneMatch(e -> e.config().carries(op))) {
                issues.add(Issue.error(
                                "lb.unroutable_type",
                                "%s receives %s requests but none of its outgoing links carry %ss."
                                        .formatted(node.name(), op.json(), op.json()))
                        .atNode(node.id())
                        .withHint("Add '%s' to the request types of one of its outgoing links.".formatted(op.json())));
            }
        }
        return issues;
    }
}
