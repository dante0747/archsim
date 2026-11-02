package io.archsim.model.component.standard;

import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.client.TrafficSourceSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.external.ExternalApiSpec;
import io.archsim.model.component.kubernetes.KubernetesClusterSpec;
import io.archsim.model.component.loadbalancer.LoadBalancerSpec;
import io.archsim.model.component.queue.MessageQueueSpec;
import io.archsim.model.component.service.ServiceSpec;
import java.util.List;

/** ArchSim's own component types, in the order the Python engine's default registry has them. */
public final class StandardComponents {

    private static final ComponentRegistry REGISTRY = new ComponentRegistry(List.of(
            TrafficSourceSpec.CLIENTS,
            TrafficSourceSpec.MOBILE_APP,
            TrafficSourceSpec.WEB_CLIENT,
            TrafficSourceSpec.API_CONSUMER,
            new LoadBalancerSpec(),
            new ServiceSpec(),
            new CacheSpec(),
            new DatabaseSpec(),
            new ExternalApiSpec(),
            new MessageQueueSpec(),
            new KubernetesClusterSpec()));

    private StandardComponents() {}

    /** A registry of ArchSim's own types (it is immutable, so one is shared). */
    public static ComponentRegistry registry() {
        return REGISTRY;
    }
}
