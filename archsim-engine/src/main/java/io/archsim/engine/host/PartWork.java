package io.archsim.engine.host;

import java.util.Optional;

import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.service.ServiceSpec;

/**
 * The CPU work a part does at a load, read from its settings as a design that may not be
 * valid yet is read (ADR-0038).
 *
 * @param busy the threads it keeps busy at full speed, over all its instances
 * @param busiest the threads its busiest instance keeps busy
 * @param cores the vCPU of one instance
 */
public record PartWork(double busy, double busiest, double cores) {

	private static final double MILLIS_PER_SECOND = 1000.0;

	/** A database spends half its service time on the CPU unless it says otherwise. */
	private static final double DATABASE_CPU_SHARE = 0.5;

	/**
	 * The work of a service, database or cache with a CPU size, for sizing: a part
	 * without one counts with one vCPU, as it is about to get a size.
	 * @param type the part's component type
	 * @param settings its settings over its type's defaults
	 * @param load the requests it carries
	 * @return its work; empty for another type, or a serverless function
	 */
	public static Optional<PartWork> forSizing(String type, LenientSettings settings, NodeLoad load) {
		double cpu = settings.number("cpuCores").orElse(0.0);
		double cores = (cpu > 0) ? cpu : 1.0;
		return switch (type) {
			case ServiceSpec.TYPE -> service(settings, load, cores);
			case DatabaseSpec.TYPE -> Optional.of(database(settings, load, cores));
			case CacheSpec.TYPE -> Optional.of(cache(settings, load, cores));
			default -> Optional.empty();
		};
	}

	private static Optional<PartWork> service(LenientSettings settings, NodeLoad load, double cores) {
		if (settings.is("platform", "serverless")) {
			return Optional.empty();
		}
		double share = settings.number("cpuShare").orElse(1.0);
		double replicas = Math.max(1.0, settings.nonZero("replicas").orElse(1.0));
		if (settings.isTrue("autoscaling")) {
			replicas = Math.max(replicas, settings.nonZero("maxReplicas").orElse(1.0));
		}
		double total = load.total();
		double ms = settings.meanMs("serviceTimeMs") * share
				* settings.loadCurve("serviceTimeByLoad").factorAt(total / replicas);
		double busy = total * ms / MILLIS_PER_SECOND;
		return Optional.of(new PartWork(busy, busy / replicas, cores));
	}

	private static PartWork database(LenientSettings settings, NodeLoad load, double cores) {
		double share = settings.number("cpuShare").orElse(DATABASE_CPU_SHARE);
		double shards = Math.max(1.0, settings.nonZero("shards").orElse(1.0));
		double copies = 1.0 + Math.max(0.0, settings.number("readReplicas").orElse(0.0));
		double readMs = settings.meanMs("readServiceTimeMs");
		double writeMs = settings.meanMs("writeServiceTimeMs");
		double busy = (load.read() * readMs + load.write() * writeMs) * share / MILLIS_PER_SECOND;
		double primary = ((load.read() / copies) * readMs + load.write() * writeMs) * share / MILLIS_PER_SECOND
				/ shards;
		return new PartWork(busy, primary, cores);
	}

	private static PartWork cache(LenientSettings settings, NodeLoad load, double cores) {
		double shards = Math.max(1.0, settings.nonZero("shards").orElse(1.0));
		double busy = load.total() * settings.meanMs("serviceTimeMs") / MILLIS_PER_SECOND;
		return new PartWork(busy, busy / shards, cores);
	}

}
