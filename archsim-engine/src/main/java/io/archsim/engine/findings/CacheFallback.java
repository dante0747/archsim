package io.archsim.engine.findings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.EdgeSpec;

/**
 * The advice after a cache's outage hurt (ADR-0082). Its callers fall back to the data
 * store when they carry on after a failed lookup and read the store on a miss: the advice
 * asks a caller only for the part of that it lacks, and, when every caller does both, for
 * capacity for the fallback's reads.
 */
final class CacheFallback {

	private static final String PREFIX = "Treat cache errors as misses and fall back to the database: ";

	private static final String SUFFIX = ", or run a replicated cache.";

	/** The advice when no caller falls back, or no service looks the cache up. */
	static final String FALL_BACK = PREFIX + "turn on 'Carry on if this call fails' on the link to the cache and set "
			+ "the link to the database to 'Reads only on a cache miss' (check the database can take every read)"
			+ SUFFIX;

	private final ArchitectureDocument resolved;

	private final Map<String, String> labels;

	private CacheFallback(ArchitectureDocument resolved, Map<String, String> labels) {
		this.resolved = resolved;
		this.labels = labels;
	}

	/**
	 * The advice after a cache's outage.
	 * @param resolved the design simulated
	 * @param cacheId the cache
	 * @param labels each part's label, by id
	 * @return the advice, asking only for what the cache's callers do not do yet
	 */
	static String afterOutage(ArchitectureDocument resolved, String cacheId, Map<String, String> labels) {
		return new CacheFallback(resolved, labels).advise(cacheId);
	}

	private String advise(String cacheId) {
		CacheCallers cacheCallers = CacheCallers.of(this.resolved, cacheId);
		List<EdgeSpec> lookups = cacheCallers.fromServices();
		List<String> callers = lookups.stream().map(EdgeSpec::source).distinct().toList();
		List<EdgeSpec> failing = lookups.stream().filter((edge) -> !edge.config().continueOnFailure()).toList();
		List<String> withoutFetch = callers.stream().filter((caller) -> cacheCallers.stores(caller).isEmpty()).toList();
		boolean noneFallsBack = failing.size() == lookups.size() && withoutFetch.size() == callers.size();
		if (lookups.isEmpty() || noneFallsBack) {
			return FALL_BACK;
		}
		if (lookups.stream().allMatch(cacheCallers::fallsBack)) {
			return capacity(cacheId, callers, cacheCallers);
		}
		return PREFIX + String.join(" and ", steps(failing, withoutFetch)) + SUFFIX;
	}

	// every caller falls back: what the fallback's reads need
	private String capacity(String cacheId, List<String> callers, CacheCallers cacheCallers) {
		List<String> stores = callers.stream()
			.flatMap((caller) -> cacheCallers.stores(caller).stream())
			.distinct()
			.toList();
		String who = names(callers);
		boolean one = callers.size() == 1;
		return (one ? "Its caller, " + who + ", already treats" : "Its callers, " + who + ", already treat")
				+ " a failed lookup on " + label(cacheId) + " as a miss and " + (one ? "reads " : "read ")
				+ names(stores)
				+ " ('Carry on if this call fails', 'Reads only on a cache miss'): what ran short was the capacity of "
				+ who + " and " + names(stores) + " for the reads " + label(cacheId) + " serves. Size them for "
				+ "those reads, or run a replicated cache so that losing one node does not move every read.";
	}

	private List<String> steps(List<EdgeSpec> failing, List<String> withoutFetch) {
		List<String> steps = new ArrayList<>();
		if (!failing.isEmpty()) {
			steps.add("turn on 'Carry on if this call fails' on " + String.join(" and ",
					failing.stream().map((edge) -> label(edge.source()) + " → " + label(edge.target())).toList()));
		}
		if (!withoutFetch.isEmpty()) {
			steps.add("give " + names(withoutFetch) + " a link to the database set to 'Reads only on a cache "
					+ "miss' (check the database can take every read)");
		}
		return steps;
	}

	private String names(List<String> ids) {
		return String.join(" and ", ids.stream().map(this::label).toList());
	}

	private String label(String id) {
		return this.labels.getOrDefault(id, id);
	}

}
