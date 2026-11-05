package io.archsim.engine.review;

import java.util.Optional;
import java.util.OptionalDouble;

import io.archsim.engine.host.HostSpeeds;
import io.archsim.engine.load.CacheMisses;
import io.archsim.engine.load.LoadEstimate;
import io.archsim.engine.load.LoadEstimator;
import io.archsim.engine.numeric.FloorDivision;
import io.archsim.engine.numeric.TrueDivision;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.engine.text.HalfUp;
import io.archsim.engine.text.NumberText;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.text.PythonFormat;

/**
 * The review's rules about resource specs: a part's CPU at the peak load, from the static
 * load estimate, a data store's hot data against its memory, and a cache's keys against
 * the workload's.
 */
final class CapacityRules {

	/** A data store keeps this share of its memory as buffer cache. */
	private static final double BUFFER_CACHE_SHARE = 0.75;

	/** A data store with less of its hot data in memory than this reads from disk. */
	private static final double BUFFER_CACHE_HIT = 0.9;

	/**
	 * Under this share of its hot data in memory, a data store's finding is of medium
	 * severity.
	 */
	private static final double POOR_BUFFER_CACHE_HIT = 0.5;

	/** A cache whose hit ratio follows from its keys' TTL and how popular they are. */
	private static final String TTL_KEYSPACE = "ttl_keyspace";

	/** A cache keeps this share of its memory for its keys. */
	private static final double KEY_MEMORY_SHARE = 0.8;

	private static final double KIB_PER_GIB = 1024;

	private final ReviewGraph graph;

	private final Findings findings;

	private final ArchitectureDocument document;

	private final CpuNeeds cpu;

	CapacityRules(ReviewGraph graph, Findings findings, ArchitectureDocument document, LoadEstimator loads) {
		this.graph = graph;
		this.findings = findings;
		this.document = document;
		double peak = document.workload().peakRate();
		LoadEstimate load = loads.estimate(document.architecture(), document.workload(), peak, CacheMisses.UNKNOWN);
		this.cpu = new CpuNeeds(graph, load,
				HostSpeeds.at(document.architecture(), load, (node) -> Optional.of(graph.settings(node.id())), peak));
	}

	/**
	 * Checks each part's resource specs.
	 */
	void check() {
		for (NodeSpec node : this.document.architecture().nodes()) {
			this.cpu.busy(node)
				.filter((busy) -> busy.share() >= CpuNeeds.LITTLE_HEADROOM)
				.ifPresent((busy) -> cpuCapacity(node.id(), busy));
			if (DatabaseSpec.TYPE.equals(node.type())) {
				bufferCache(node.id());
			}
			if (CacheSpec.TYPE.equals(node.type()) && this.graph.hitModelIs(node.id(), TTL_KEYSPACE)) {
				cacheCapacity(node.id());
			}
		}
	}

	private void cpuCapacity(String id, CpuNeeds.Busy busy) {
		boolean over = busy.share() >= 1;
		String name = this.graph.label(id);
		this.findings.add(ReviewRule.CPU_CAPACITY, over ? Severity.HIGH : Severity.MEDIUM, Involved.node(id),
				new Wording(over ? name + " needs more CPU at peak" : name + " has little CPU headroom",
						"At the " + HalfUp.grouped(this.document.workload().peakRate()) + " req/s peak about "
								+ HalfUp.grouped(busy.load()) + " req/s reach " + name + "; " + busy.how()
								+ ", so its CPU would be " + ReviewText.percent(busy.share()) + " busy"
								+ (over ? " and requests queue for a core" : "")
								+ ". This is a static estimate (first attempts, fixed hit ratios); a run measures it.",
						"Give it more CPU cores or replicas, or reduce the CPU work per request."));
	}

	private void bufferCache(String id) {
		LenientSettings settings = this.graph.settings(id);
		OptionalDouble memory = settings.number("memoryGib");
		OptionalDouble workingSet = settings.number("workingSetGib");
		if (memory.isEmpty() || workingSet.isEmpty()) {
			return;
		}
		double perInstance = workingSet.getAsDouble()
				/ ReviewNumbers.larger(1.0, settings.nonZero("shards").orElse(1.0));
		if (perInstance == 0) {
			// no hot data per instance: all of it fits
			return;
		}
		double hit = ReviewNumbers.smaller(1.0,
				TrueDivision.of(BUFFER_CACHE_SHARE * memory.getAsDouble(), perInstance));
		if (hit >= BUFFER_CACHE_HIT) {
			return;
		}
		this.findings.add(ReviewRule.BUFFER_CACHE, (hit < POOR_BUFFER_CACHE_HIT) ? Severity.MEDIUM : Severity.LOW,
				Involved.node(id),
				new Wording(this.graph.label(id) + "'s hot data does not fit in memory",
						"75% of " + ReviewText.plain(memory) + " GiB is buffer cache for about "
								+ NumberText.plain(HalfUp.round(perInstance, 1)) + " GiB of hot data per instance, so "
								+ "about " + ReviewText.percent(1 - hit) + " of reads go to disk.",
						"Give each instance more memory, shard the data, or cache the hottest reads in front."));
	}

	private void cacheCapacity(String id) {
		LenientSettings settings = this.graph.settings(id);
		OptionalDouble memory = settings.number("memoryGib");
		if (memory.isEmpty()) {
			return;
		}
		double item = settings.number("itemSizeKb").orElse(1.0);
		if (item == 0) {
			// items of no size: memory holds every key
			return;
		}
		double keys = ReviewNumbers.larger(1, ReviewNumbers
			.whole(FloorDivision.of(KEY_MEMORY_SHARE * memory.getAsDouble() * KIB_PER_GIB * KIB_PER_GIB, item)));
		long keyCount = this.document.workload().keyCount();
		if (keys >= keyCount) {
			return;
		}
		String held = PythonFormat.format(keys, ",.0f");
		this.findings.add(ReviewRule.CACHE_CAPACITY, Severity.LOW, Involved.node(id), new Wording(
				this.graph.label(id) + " holds " + held + " of " + PythonFormat.format(keyCount, ",") + " keys",
				"80% of " + ReviewText.plain(memory) + " GiB fits about " + held + " items of " + NumberText.plain(item)
						+ " KiB; the least recently used keys are evicted, so expect a lower hit ratio than with "
						+ "unbounded memory.",
				"Add memory if the hit ratio matters, or shorten what you cache."));
	}

}
