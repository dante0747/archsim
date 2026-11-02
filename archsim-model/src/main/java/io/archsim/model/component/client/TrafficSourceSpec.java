package io.archsim.model.component.client;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.component.Port;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.issue.Issue;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A client group: it generates its share of the workload and sends it all to one entry point.
 * The four groups differ only in their defaults: generic clients, a mobile app, a web browser and
 * API consumers.
 *
 * @param <C> the group's config
 */
public final class TrafficSourceSpec<C extends Record & ClientConfig> extends ComponentSpec<C> {

    /** Declared first: the specs below are built with it. */
    private static final Port REQUESTS = Port.out(
                    "out", "Requests", "Sends every generated request to one entry point (usually a load balancer).")
            .withMinEdges(1)
            .withMaxEdges(1);

    public static final TrafficSourceSpec<TrafficSourceConfig> CLIENTS =
            new TrafficSourceSpec<>("traffic_source", "Clients / Traffic", TrafficSourceConfig.class);
    public static final TrafficSourceSpec<MobileAppConfig> MOBILE_APP =
            new TrafficSourceSpec<>("mobile_app", "Mobile app", MobileAppConfig.class);
    public static final TrafficSourceSpec<WebClientConfig> WEB_CLIENT =
            new TrafficSourceSpec<>("web_client", "Web browser", WebClientConfig.class);
    public static final TrafficSourceSpec<ApiConsumerConfig> API_CONSUMER =
            new TrafficSourceSpec<>("api_consumer", "API consumers", ApiConsumerConfig.class);

    private TrafficSourceSpec(String type, String displayName, Class<C> configType) {
        super(type, displayName, Category.TRAFFIC, configType, List.of(REQUESTS));
    }

    @Override
    public boolean isTrafficSource() {
        return true;
    }

    /** Reads when the workload has any, writes when it is not all reads. */
    @Override
    public Set<RequestType> emittedTypes(String port, Set<RequestType> incoming, C config, GraphView view) {
        return generated(view);
    }

    private static Set<RequestType> generated(GraphView view) {
        double ratio = view.workload().readRatio();
        Set<RequestType> types = EnumSet.noneOf(RequestType.class);
        if (ratio > 0) {
            types.add(RequestType.READ);
        }
        if (ratio < 1) {
            types.add(RequestType.WRITE);
        }
        return types;
    }

    @Override
    public List<Issue> validate(NodeSpec node, C config, GraphView view) {
        List<Issue> issues = new ArrayList<>();
        Set<RequestType> generated = generated(view);
        for (EdgeSpec edge : view.outEdges(node.id())) {
            Set<RequestType> missing = EnumSet.noneOf(RequestType.class);
            missing.addAll(generated);
            missing.removeAll(edge.config().requestTypes());
            if (!missing.isEmpty()) {
                String kinds = missing.stream().map(RequestType::json).collect(Collectors.joining(" and "));
                issues.add(Issue.error(
                                "source.unrouted_types",
                                "The workload generates %s requests but link '%s' from %s does not carry them."
                                        .formatted(kinds, edge.id(), node.name()))
                        .atNode(node.id())
                        .atEdge(edge.id())
                        .withHint("Add %s to the link's request types, or change the read ratio.".formatted(kinds)));
            }
            if (edge.config().timeoutMs() != null || edge.config().maxAttempts() != 1) {
                issues.add(Issue.warning(
                                "source.edge_retry_ignored",
                                ("Timeout/retry settings on link '%s' are ignored; the traffic source's own client"
                                                + " timeout and attempts apply.")
                                        .formatted(edge.id()))
                        .atEdge(edge.id())
                        .withHint("Configure client timeout and attempts on the traffic source node."));
            }
        }
        return issues;
    }
}
