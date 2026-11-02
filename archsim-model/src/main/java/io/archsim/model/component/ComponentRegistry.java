package io.archsim.model.component;

import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.client.TrafficSourceSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.external.ExternalApiSpec;
import io.archsim.model.component.kubernetes.KubernetesClusterSpec;
import io.archsim.model.component.loadbalancer.LoadBalancerSpec;
import io.archsim.model.component.queue.MessageQueueSpec;
import io.archsim.model.component.service.ServiceSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The component types a design may use, by type name, in the order they were added (which is the
 * order messages list them in).
 */
public final class ComponentRegistry {

    private final Map<String, ComponentSpec<?>> specs = new LinkedHashMap<>();

    public ComponentRegistry(List<? extends ComponentSpec<?>> specs) {
        for (ComponentSpec<?> spec : specs) {
            if (this.specs.putIfAbsent(spec.type(), spec) != null) {
                throw new IllegalArgumentException("component type '%s' is already registered".formatted(spec.type()));
            }
        }
    }

    /** ArchSim's own component types, as the Python engine's default registry has them. */
    public static ComponentRegistry standard() {
        return new ComponentRegistry(List.of(
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
    }

    /** The type named {@code type}, if it is registered. */
    public Optional<ComponentSpec<?>> find(String type) {
        return Optional.ofNullable(specs.get(type));
    }

    public boolean contains(String type) {
        return specs.containsKey(type);
    }

    /** Every registered type name, in order. */
    public List<String> types() {
        return List.copyOf(specs.keySet());
    }

    public List<ComponentSpec<?>> specs() {
        return List.copyOf(specs.values());
    }
}
