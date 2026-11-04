package io.archsim.engine.load;

import java.util.Map;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.kubernetes.KubernetesClusterSpec;
import io.archsim.model.component.service.ServiceSpec;

/** The family of parts a component type belongs to, for how load flows through it. */
public enum ComponentFamily {

	/** Clients, which generate the workload. */
	TRAFFIC,

	/** Parts that pass each request on to one target, such as load balancers. */
	NETWORK,

	/** Parts that run application code. */
	COMPUTE,

	/** Caches, which pass on their misses. */
	CACHE,

	/** Databases. */
	DATABASE,

	/** Message queues and streams, which pass each message to one consumer. */
	MESSAGING,

	/** Third-party services. */
	EXTERNAL,

	/** Anything else, including types the registry does not know. */
	OTHER;

	private static final Map<String, ComponentFamily> STANDARD = Map.ofEntries(Map.entry("traffic_source", TRAFFIC),
			Map.entry("mobile_app", TRAFFIC), Map.entry("web_client", TRAFFIC), Map.entry("api_consumer", TRAFFIC),
			Map.entry("load_balancer", NETWORK), Map.entry(ServiceSpec.TYPE, COMPUTE),
			Map.entry(KubernetesClusterSpec.TYPE, COMPUTE), Map.entry(CacheSpec.TYPE, CACHE),
			Map.entry(DatabaseSpec.TYPE, DATABASE), Map.entry("message_queue", MESSAGING),
			Map.entry("external_api", EXTERNAL));

	private static final Map<Category, ComponentFamily> BY_CATEGORY = Map.of(Category.TRAFFIC, TRAFFIC,
			Category.NETWORK, NETWORK, Category.COMPUTE, COMPUTE, Category.STORAGE, DATABASE, Category.MESSAGING,
			MESSAGING, Category.EXTERNAL, EXTERNAL);

	/**
	 * The family of a component type: ArchSim's own types by name, any other by its
	 * category.
	 * @param type the component type
	 * @param registry the component types the design may use
	 * @return its family; {@link #OTHER} for a type the registry does not know
	 */
	public static ComponentFamily of(String type, ComponentRegistry registry) {
		ComponentFamily standard = STANDARD.get(type);
		if (standard != null) {
			return standard;
		}
		return registry.find(type).map(ComponentFamily::ofSpec).orElse(OTHER);
	}

	private static ComponentFamily ofSpec(ComponentSpec<?> spec) {
		return spec.isTrafficSource() ? TRAFFIC : BY_CATEGORY.getOrDefault(spec.category(), OTHER);
	}

}
