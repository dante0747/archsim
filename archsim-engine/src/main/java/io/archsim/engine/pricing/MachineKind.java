package io.archsim.engine.pricing;

import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.kubernetes.KubernetesClusterSpec;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.pricing.SkuCategory;

/**
 * Which price list a part's machines come from: compute instances, or a managed database,
 * cache or stream engine's.
 *
 * @param category the price list
 * @param engine the engine the machine must run, for a database, cache or stream;
 * {@code null} for compute
 */
public record MachineKind(SkuCategory category, @Nullable String engine) {

	/**
	 * Managed services: the provider sizes them, so they never run on an instance type.
	 */
	public static final Set<String> MANAGED_TECHNOLOGIES = Set.of("aws_alb", "api_gateway", "cdn", "dynamodb", "sqs",
			"pubsub");

	private static final MachineKind COMPUTE = new MachineKind(SkuCategory.COMPUTE, null);

	private static final Set<String> NOT_ON_MACHINES = Set.of("kubernetes", "serverless");

	/**
	 * The price list a part's machines come from, read from its settings as a design that
	 * may not be valid yet is read (ADR-0038).
	 * @param nodeType the part's component type
	 * @param settings the part's settings
	 * @return the kind; empty when the part is not billed per machine (clients,
	 * third-party APIs, services on Kubernetes or serverless, managed services)
	 */
	public static Optional<MachineKind> of(String nodeType, LenientSettings settings) {
		String technology = settings.text("technology").orElse(null);
		if (technology != null && MANAGED_TECHNOLOGIES.contains(technology)) {
			return Optional.empty();
		}
		return switch (nodeType) {
			case ServiceSpec.TYPE -> settings.text("platform").filter(NOT_ON_MACHINES::contains).isPresent()
					? Optional.empty() : Optional.of(COMPUTE);
			case DatabaseSpec.TYPE ->
				Optional.of(new MachineKind(SkuCategory.DATABASE, orElse(technology, "postgresql")));
			case CacheSpec.TYPE -> Optional.of(new MachineKind(SkuCategory.CACHE, orElse(technology, "redis")));
			case "message_queue" ->
				Optional.of("kafka".equals(technology) ? new MachineKind(SkuCategory.STREAM, "kafka") : COMPUTE);
			case "load_balancer", KubernetesClusterSpec.TYPE -> Optional.of(COMPUTE);
			default -> Optional.empty();
		};
	}

	/**
	 * Whether the part runs on compute instances of its own.
	 * @return {@code true} for the compute price list
	 */
	public boolean isCompute() {
		return this.category == SkuCategory.COMPUTE;
	}

	/** Python's {@code technology or fallback}: an empty technology is none. */
	private static String orElse(@Nullable String technology, String fallback) {
		return (technology == null || technology.isEmpty()) ? fallback : technology;
	}

}
