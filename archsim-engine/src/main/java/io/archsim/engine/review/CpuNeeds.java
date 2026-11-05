package io.archsim.engine.review;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import io.archsim.engine.load.LoadEstimate;
import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.numeric.TrueDivision;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.engine.settings.LoadCurve;
import io.archsim.engine.text.HalfUp;
import io.archsim.engine.text.NumberText;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.NodeSpec;

/**
 * How busy a part's CPU is at the peak, from the static load estimate (first attempts,
 * fixed hit ratios): a service's replicas at their CPU work per request on their load
 * curve, a data store's primaries, a cache's commands, slowed where threads share cores.
 */
final class CpuNeeds {

	/** From this share of its CPU busy on, a part has little headroom. */
	static final double LITTLE_HEADROOM = 0.8;

	private static final double MILLIS_PER_SECOND = 1000;

	/**
	 * The share of a request's time a data store spends on CPU when its config does not
	 * say.
	 */
	private static final double DATABASE_CPU_SHARE = 0.5;

	private final ReviewGraph graph;

	private final LoadEstimate load;

	private final Map<String, Double> speeds;

	/** Where threads that slow each other down share cores, in words. */
	private final String sharedCores;

	/**
	 * Works out CPU needs at the peak.
	 * @param graph the design
	 * @param load each part's load at the peak
	 * @param speeds the speed of each part slowed by threads that share cores
	 */
	CpuNeeds(ReviewGraph graph, LoadEstimate load, Map<String, Double> speeds) {
		this.graph = graph;
		this.load = load;
		this.speeds = speeds;
		this.sharedCores = (graph.architecture().host() != null) ? "the host machine" : "its hyperthreads";
	}

	/**
	 * How busy a part's CPU is at the peak.
	 * @param node the part
	 * @return its busy share and how it was worked out; empty for a part without a CPU
	 * size or a load, or of another type
	 */
	Optional<Busy> busy(NodeSpec node) {
		LenientSettings settings = this.graph.settings(node.id());
		OptionalDouble cpuCores = settings.number("cpuCores");
		Optional<NodeLoad> carried = this.load.node(node.id());
		if (cpuCores.isEmpty() || carried.isEmpty() || carried.get().total() <= 0) {
			return Optional.empty();
		}
		double cores = cpuCores.getAsDouble();
		Optional<Busy> busy = switch (node.type()) {
			case ServiceSpec.TYPE ->
				this.graph.serverless(node.id()) ? Optional.empty() : service(settings, cores, carried.get());
			case DatabaseSpec.TYPE -> Optional.of(database(settings, cores, carried.get()));
			case CacheSpec.TYPE -> cache(settings, cores, carried.get());
			default -> Optional.empty();
		};
		Double speed = this.speeds.get(node.id());
		return (speed != null) ? busy.map((full) -> full.slowed(speed, this.sharedCores)) : busy;
	}

	/**
	 * A service's replicas, at their most, each serving the rate at which its cores fill;
	 * not for serverless functions.
	 * @param settings its settings
	 * @param cores its vCPU per replica
	 * @param load its load
	 * @return how busy it is; empty for a service without work per request
	 */
	private static Optional<Busy> service(LenientSettings settings, double cores, NodeLoad load) {
		double ms = settings.meanMs("serviceTimeMs") * settings.number("cpuShare").orElse(1.0);
		if (ms == 0) {
			return Optional.empty();
		}
		double configuredReplicas = settings.nonZero("replicas").orElse(1.0);
		double replicas = settings.isTrue("autoscaling")
				? ReviewNumbers.larger(settings.nonZero("maxReplicas").orElse(1.0), configuredReplicas)
				: ReviewNumbers.larger(1.0, configuredReplicas);
		LoadCurve curve = settings.loadCurve("serviceTimeByLoad");
		double perReplica = curve.capacityRps(cores * MILLIS_PER_SECOND / ms);
		double capacity = perReplica * replicas;
		double work = ms * curve.factorAt(perReplica);
		return Optional.of(new Busy(TrueDivision.of(load.total(), capacity),
				NumberText.plain(cores) + " vCPU × " + NumberText.plain(replicas) + " replica"
						+ ((replicas == 1) ? "" : "s") + " at " + HalfUp.fixed(work, 1)
						+ " ms of CPU work per request handle about " + HalfUp.grouped(capacity) + " req/s",
				load.total()));
	}

	/**
	 * A data store's primaries: the reads its replicas leave them and every write.
	 * @param settings its settings
	 * @param cores its vCPU per instance
	 * @param load its load
	 * @return how busy each primary is
	 */
	private static Busy database(LenientSettings settings, double cores, NodeLoad load) {
		double read = settings.meanMs("readServiceTimeMs");
		double write = settings.meanMs("writeServiceTimeMs");
		double share = settings.number("cpuShare").orElse(DATABASE_CPU_SHARE);
		double shards = ReviewNumbers.larger(1.0, settings.nonZero("shards").orElse(1.0));
		double copies = 1 + ReviewNumbers.larger(0.0, settings.nonZero("readReplicas").orElse(0.0));
		double primary = ((load.read() / copies) * read + load.write() * write) * share / MILLIS_PER_SECOND / shards;
		return new Busy(TrueDivision.of(primary, cores),
				"each primary needs about " + HalfUp.fixed(primary, 1) + " of its " + NumberText.plain(cores)
						+ " vCPU (" + ReviewText.percent(share) + " of " + HalfUp.fixed(read, 1) + " ms reads and "
						+ HalfUp.fixed(write, 1) + " ms writes)",
				load.total());
	}

	private static Optional<Busy> cache(LenientSettings settings, double cores, NodeLoad load) {
		double ms = settings.meanMs("serviceTimeMs");
		if (ms == 0) {
			return Optional.empty();
		}
		double need = load.total() * ms / MILLIS_PER_SECOND;
		return Optional.of(new Busy(TrueDivision.of(need, cores),
				"commands need about " + HalfUp.fixed(need, 1) + " of its " + NumberText.plain(cores) + " vCPU",
				load.total()));
	}

	/**
	 * How busy a part's CPU is.
	 *
	 * @param share the share of its CPU busy
	 * @param how how that was worked out, in words
	 * @param load the requests per second it carries at the peak
	 */
	record Busy(double share, String how, double load) {

		/**
		 * As busy on threads that share cores.
		 * @param speed the threads' speed, as a share of full speed
		 * @param where where they share cores: the host machine, or the part's own
		 * hyperthreads
		 * @return the busy share at that speed
		 */
		Busy slowed(double speed, String where) {
			return new Busy(TrueDivision.of(this.share, speed),
					this.how + " at full speed, at " + HalfUp.fixed(speed, 2) + " of it on " + where, this.load);
		}

	}

}
