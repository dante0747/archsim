package io.archsim.engine.estimate;

import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.host.HostSpeeds;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.document.HostSpec;

/**
 * Whether a part runs on its own hyperthread pairs (schema 1.7, and by default from 1.11,
 * ADR-0091), whose CPU fills, as its load grows, where every vCPU is busy at the sibling
 * speed.
 */
final class OwnPairs {

	private OwnPairs() {
	}

	/**
	 * The sibling speed of a part's own pairs.
	 * @param config the part's config, as resolved: a sibling speed the engine inferred
	 * is in it
	 * @param host the design's host machine; {@code null} when it has none
	 * @return the speed; empty for a part of another type, of fewer than 2 vCPU, on whole
	 * cores, or on a design with a host, whose threads are shared
	 */
	static OptionalDouble siblingSpeed(Record config, @Nullable HostSpec host) {
		return switch (config) {
			case ServiceConfig service -> of(host, service.cpuCores(), service.siblingSpeed());
			case DatabaseConfig database -> of(host, database.cpuCores(), database.siblingSpeed());
			case CacheConfig cache -> of(host, cache.cpuCores(), cache.siblingSpeed());
			default -> OptionalDouble.empty();
		};
	}

	private static OptionalDouble of(@Nullable HostSpec host, @Nullable Double cores, @Nullable Double siblingSpeed) {
		return (cores != null) ? HostSpeeds.ownPairsSpeed(host, cores, siblingSpeed) : OptionalDouble.empty();
	}

}
